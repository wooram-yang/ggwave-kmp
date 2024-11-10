package com.example.ggwavekmp

import CaptureSoundListener
import PlaySoundListener
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AudioToolbox.AudioQueueAllocateBuffer
import platform.AudioToolbox.AudioQueueBufferRef
import platform.AudioToolbox.AudioQueueBufferRefVar
import platform.AudioToolbox.AudioQueueDispose
import platform.AudioToolbox.AudioQueueEnqueueBuffer
import platform.AudioToolbox.AudioQueueNewInput
import platform.AudioToolbox.AudioQueueRef
import platform.AudioToolbox.AudioQueueRefVar
import platform.AudioToolbox.AudioQueueStart
import platform.AudioToolbox.AudioQueueStop
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.AudioStreamPacketDescription
import platform.CoreAudioTypes.AudioTimeStamp
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kLinearPCMFormatFlagIsSignedInteger
import platform.CoreFoundation.CFRunLoopGetCurrent
import platform.CoreFoundation.CFRunLoopRun
import platform.CoreFoundation.kCFRunLoopCommonModes
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSMutableData
import platform.Foundation.appendData
import platform.Foundation.create
import platform.darwin.NSObject
import platform.darwin.OSStatus
import platform.darwin.UInt32
import kotlin.experimental.and

var willStopRecording = false
var outsiderGGWave: GGWave? = null
fun processCaptureData(byteData: ByteArray) {
    outsiderGGWave?.processCaptureData(byteData = byteData)
}

@OptIn(ExperimentalForeignApi::class)
fun audioQueueInputCallback(
    inUserData: COpaquePointer?,
    inAQ: AudioQueueRef?,
    inBuffer: AudioQueueBufferRef?,
    inStartTime: CPointer<AudioTimeStamp>?,
    inNumberPacketDescriptions: UInt32,
    inPacketDescs: CPointer<AudioStreamPacketDescription>?
) {
    if(willStopRecording) {
        return
    }

    inBuffer?.let {
        val audioData = inBuffer.pointed.mAudioData!!.reinterpret<ByteVar>()
        val audioDataSize = inBuffer.pointed.mAudioDataByteSize.toInt()
        val audioBytes = audioData.readBytes(audioDataSize)

        processCaptureData(byteData = audioBytes)
    }

    AudioQueueEnqueueBuffer(inAQ, inBuffer, 0u, null)
}

object IOSCoreManager: BaseCoreManager {
    private const val INPUT_SAMPLE_RATE = 44100.0
    private const val OUTPUT_SAMPLE_RATE = 48000.0
    private const val BUFFER_SIZE = 16 * 1024
    private const val BUFFER_NUM = 3

    override var ggWave: GGWave = GGWaveFactory.createInstance()
    override lateinit var playSoundListener: PlaySoundListener
    override lateinit var captureSoundListener: CaptureSoundListener

    override var messageWillBeSent: String = ""

    private val scope = CoroutineScope(Job() + Dispatchers.Default)

    private var encodedDataArray: ShortArray? = null
    private var audioPlayer: AVAudioPlayer? = null

    @OptIn(ExperimentalForeignApi::class)
    private var audioQueueRef: AudioQueueRefVar? = null

    init {
        ggWave.delegate = this
        ggWave.initNative()

        outsiderGGWave = ggWave
    }

    override fun startCapturing() {
        capture()
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun stopCapturing() {
        willStopRecording = true

        AudioQueueStop(audioQueueRef?.value, true)
        AudioQueueDispose(audioQueueRef?.value, true)
        audioQueueRef = null
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun capture() {
        scope.launch {
            memScoped {
                willStopRecording = false

                val audioFormat = alloc<AudioStreamBasicDescription>().apply {
                    mSampleRate = INPUT_SAMPLE_RATE
                    mFormatID = kAudioFormatLinearPCM
                    mFramesPerPacket = 1u
                    mChannelsPerFrame = 1u
                    mBytesPerFrame = 2u
                    mBytesPerPacket = 2u
                    mBitsPerChannel = 16u
                    mReserved = 0u
                    mFormatFlags = kLinearPCMFormatFlagIsSignedInteger
                }
                audioQueueRef = alloc<AudioQueueRefVar>()

                val callback = staticCFunction(::audioQueueInputCallback)
                val status: OSStatus = AudioQueueNewInput(
                    audioFormat.ptr,
                    callback,
                    null,
                    CFRunLoopGetCurrent(),
                    kCFRunLoopCommonModes,
                    0u,
                    audioQueueRef?.ptr
                )

                if (status == 0) {
                    for (i in 0 until BUFFER_NUM) {
                        val bufferRefVar: AudioQueueBufferRefVar = alloc<AudioQueueBufferRefVar>()
                        AudioQueueAllocateBuffer(audioQueueRef?.value, BUFFER_SIZE.toUInt(), bufferRefVar.ptr)
                        AudioQueueEnqueueBuffer(audioQueueRef?.value, bufferRefVar.value, 0u, null)
                    }

                    AudioQueueStart(audioQueueRef?.value, null)
                    CFRunLoopRun()
                }
            }
        }
    }

    override fun startPlayback() {
        ggWave.sendMessage(messageWillBeSent)
    }

    override fun stopPlayback() {
        audioPlayer?.stop()
        audioPlayer = null

        encodedDataArray = null

        playSoundListener.onPlayEnded()
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun play() {
        scope.launch {
            encodedDataArray?.let { dataArray ->
                val convertedAudioData = convertFromShortToByteArray(dataArray)

                val completeAudioData = NSMutableData()
                completeAudioData.appendData(getWaveHeaderData(convertedAudioData.size).toNSData())
                completeAudioData.appendData(convertedAudioData.toNSData())

                val errorPtr = nativeHeap.alloc<ObjCObjectVar<NSError?>>()
                audioPlayer = AVAudioPlayer(data = completeAudioData, fileTypeHint = "wav", error = errorPtr.ptr)
                audioPlayer?.setDelegate(AVAudioPlayerDelegate())
                audioPlayer?.play()
                nativeHeap.free(errorPtr)
            }
        }
    }

    override fun playing() {}

    @OptIn(ExperimentalForeignApi::class)
    override fun onNativeReceivedMessage(data: ByteArray) {
        captureSoundListener.onReceivedMessage(data.toKString())
    }

    override fun onNativeMessageEncoded(data: ShortArray) {
        encodedDataArray = data
        play()
    }

    private fun convertFromShortToByteArray(dataArray: ShortArray): ByteArray {
        val resultArray = ByteArray(2 * dataArray.size)
        var i = 0
        for(shortValue in dataArray) {
            resultArray[i++] = (shortValue and 0xFF).toByte()
            resultArray[i++] = ((shortValue.toInt() ushr 8) and 0xFF).toByte()
        }

        return resultArray
    }

    private fun getWaveHeaderData(totalDataLength: Int): ByteArray {
        val totalAudioLen: ULong = totalDataLength.toULong()
        val totalDataLen: ULong = totalAudioLen + 44u
        val longSampleRate: ULong = OUTPUT_SAMPLE_RATE.toULong()
        val channels: ULong = 1u
        val byteRate: ULong = (16u * longSampleRate * channels) / 8u

        val headerByteArray = ByteArray(44)
        headerByteArray[0] = 'R'.code.toByte() // RIFF/WAVE header
        headerByteArray[1] = 'I'.code.toByte()
        headerByteArray[2] = 'F'.code.toByte()
        headerByteArray[3] = 'F'.code.toByte()
        headerByteArray[4] = (totalDataLen and 255u).toByte()
        headerByteArray[5] = ((totalDataLen shr 8) and 255u).toByte()
        headerByteArray[6] = ((totalDataLen shr 16) and 255u).toByte()
        headerByteArray[7] = ((totalDataLen shr 24) and 255u).toByte()
        headerByteArray[8] = 'W'.code.toByte()
        headerByteArray[9] = 'A'.code.toByte()
        headerByteArray[10] = 'V'.code.toByte()
        headerByteArray[11] = 'E'.code.toByte()
        headerByteArray[12] = 'f'.code.toByte() // 'fmt ' chunk
        headerByteArray[13] = 'm'.code.toByte()
        headerByteArray[14] = 't'.code.toByte()
        headerByteArray[15] = ' '.code.toByte()
        headerByteArray[16] = 16 // 4 bytes: size of 'fmt ' chunk
        headerByteArray[17] = 0
        headerByteArray[18] = 0
        headerByteArray[19] = 0
        headerByteArray[20] = 1 // format = 1 for pcm and 2 for byte integer
        headerByteArray[21] = 0
        headerByteArray[22] = channels.toByte()
        headerByteArray[23] = 0
        headerByteArray[24] = (longSampleRate and 255u).toByte()
        headerByteArray[25] = ((longSampleRate shr 8) and 255u).toByte()
        headerByteArray[26] = ((longSampleRate shr 16) and 255u).toByte()
        headerByteArray[27] = ((longSampleRate shr 24) and 255u).toByte()
        headerByteArray[28] = (byteRate and 255u).toByte()
        headerByteArray[29] = ((byteRate shr 8) and 255u).toByte()
        headerByteArray[30] = ((byteRate shr 16) and 255u).toByte()
        headerByteArray[31] = ((byteRate shr 24) and 255u).toByte()
        headerByteArray[32] = (16u * 1u / 8u).toByte() // block align
        headerByteArray[33] = 0
        headerByteArray[34] = 16 // bits per sample
        headerByteArray[35] = 0
        headerByteArray[36] = 'd'.code.toByte()
        headerByteArray[37] = 'a'.code.toByte()
        headerByteArray[38] = 't'.code.toByte()
        headerByteArray[39] = 'a'.code.toByte()
        headerByteArray[40] = (totalAudioLen and 255u).toByte()
        headerByteArray[41] = ((totalAudioLen shr 8) and 255u).toByte()
        headerByteArray[42] = ((totalAudioLen shr 16) and 255u).toByte()
        headerByteArray[43] = ((totalAudioLen shr 24) and 255u).toByte()

        return headerByteArray
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    fun ByteArray.toNSData() = this.usePinned {
        NSData.create(bytes = it.addressOf(0), length = this.size.convert())
    }

    class AVAudioPlayerDelegate: NSObject(), AVAudioPlayerDelegateProtocol {
        override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
            stopPlayback()
        }

        override fun audioPlayerDecodeErrorDidOccur(player: AVAudioPlayer, error: NSError?) {
            stopPlayback()
        }
    }
}

actual object CoreManagerFactory {
    actual fun createInstance(): BaseCoreManager = IOSCoreManager
}