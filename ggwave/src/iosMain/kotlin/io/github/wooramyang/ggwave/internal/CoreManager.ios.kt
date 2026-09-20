package io.github.wooramyang.ggwave.internal

import io.github.wooramyang.ggwave.GgwaveConfig
import io.github.wooramyang.ggwave.GgwaveException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import platform.CoreFoundation.CFRunLoopStop
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

internal var activeIosRuntime: IosGgwaveRuntime? = null

@OptIn(ExperimentalForeignApi::class)
internal fun audioQueueInputCallback(
    inUserData: COpaquePointer?,
    inAQ: AudioQueueRef?,
    inBuffer: AudioQueueBufferRef?,
    inStartTime: CPointer<AudioTimeStamp>?,
    inNumberPacketDescriptions: UInt32,
    inPacketDescs: CPointer<AudioStreamPacketDescription>?,
) {
    val runtime = activeIosRuntime ?: return
    if (runtime.captureJob?.isActive != true) return

    inBuffer?.let {
        val audioData = inBuffer.pointed.mAudioData!!.reinterpret<ByteVar>()
        val audioDataSize = inBuffer.pointed.mAudioDataByteSize.toInt()
        val audioBytes = audioData.readBytes(audioDataSize)
        runtime.onCaptureBytes(audioBytes)
    }

    AudioQueueEnqueueBuffer(inAQ, inBuffer, 0u, null)
}

@OptIn(ExperimentalForeignApi::class)
internal class IosGgwaveRuntime(
    private val config: GgwaveConfig,
) : GgwaveRuntime {
    private val inputSampleRate = 44100.0
    private val outputSampleRate = config.sampleRate.toDouble()
    private val bufferSize = 16 * 1024
    private val bufferNum = 3

    private val ggWave: GGWave = createGGWave()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var onReceivedMessage: (String) -> Unit = {}
    private var onPlayEnded: () -> Unit = {}
    private var onPlayFailed: (Throwable) -> Unit = {}

    private var audioPlayer: AVAudioPlayer? = null
    private var audioQueueRef: AudioQueueRefVar? = null
    internal var captureJob: Job? = null

    init {
        ggWave.init(config)
        activeIosRuntime = this
    }

    override fun setListeners(
        onReceivedMessage: (String) -> Unit,
        onPlayEnded: () -> Unit,
        onPlayFailed: (Throwable) -> Unit,
    ) {
        this.onReceivedMessage = onReceivedMessage
        this.onPlayEnded = onPlayEnded
        this.onPlayFailed = onPlayFailed
    }

    internal fun onCaptureBytes(byteData: ByteArray) {
        val payload = ggWave.decode(byteData) ?: return
        val text = payload.toGgwavePayload()
        if (text.isNotBlank()) {
            onReceivedMessage(text)
        }
    }

    override fun startCapturing() {
        stopCapturing()
        captureJob = scope.launch {
            memScoped {
                val audioFormat = alloc<AudioStreamBasicDescription>().apply {
                    mSampleRate = inputSampleRate
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
                    audioQueueRef?.ptr,
                )

                if (status != 0) {
                    onPlayFailed(GgwaveException("AudioQueueNewInput failed: $status"))
                    return@memScoped
                }

                for (i in 0 until bufferNum) {
                    val bufferRefVar: AudioQueueBufferRefVar = alloc<AudioQueueBufferRefVar>()
                    AudioQueueAllocateBuffer(audioQueueRef?.value, bufferSize.toUInt(), bufferRefVar.ptr)
                    AudioQueueEnqueueBuffer(audioQueueRef?.value, bufferRefVar.value, 0u, null)
                }

                AudioQueueStart(audioQueueRef?.value, null)
                CFRunLoopRun()
            }
        }
    }

    override fun stopCapturing() {
        val job = captureJob
        captureJob = null
        CFRunLoopStop(CFRunLoopGetCurrent())
        AudioQueueStop(audioQueueRef?.value, true)
        AudioQueueDispose(audioQueueRef?.value, true)
        audioQueueRef = null
        job?.cancel()
    }

    override fun startPlayback(message: String) {
        val encoded = try {
            ggWave.encode(message, config.volume)
        } catch (error: Throwable) {
            onPlayFailed(error)
            return
        }
        if (encoded.isEmpty()) {
            onPlayFailed(GgwaveException("Failed to encode message"))
            return
        }
        play(encoded)
    }

    override fun stopPlayback() {
        audioPlayer?.stop()
        audioPlayer = null
    }

    private fun play(dataArray: ShortArray) {
        scope.launch {
            try {
                val convertedAudioData = convertFromShortToByteArray(dataArray)
                val completeAudioData = NSMutableData()
                completeAudioData.appendData(getWaveHeaderData(convertedAudioData.size).toNSData())
                completeAudioData.appendData(convertedAudioData.toNSData())

                val errorPtr = nativeHeap.alloc<ObjCObjectVar<NSError?>>()
                audioPlayer = AVAudioPlayer(data = completeAudioData, fileTypeHint = "wav", error = errorPtr.ptr)
                val error = errorPtr.value
                nativeHeap.free(errorPtr)
                if (error != null) {
                    onPlayFailed(GgwaveException(error.localizedDescription))
                    return@launch
                }
                audioPlayer?.setDelegate(AVAudioPlayerDelegate())
                audioPlayer?.play()
            } catch (error: Throwable) {
                onPlayFailed(error)
            }
        }
    }

    override fun close() {
        stopCapturing()
        stopPlayback()
        ggWave.close()
        scope.cancel()
        if (activeIosRuntime === this) {
            activeIosRuntime = null
        }
    }

    private fun convertFromShortToByteArray(dataArray: ShortArray): ByteArray {
        val resultArray = ByteArray(2 * dataArray.size)
        var i = 0
        for (shortValue in dataArray) {
            resultArray[i++] = (shortValue and 0xFF).toByte()
            resultArray[i++] = ((shortValue.toInt() ushr 8) and 0xFF).toByte()
        }
        return resultArray
    }

    private fun getWaveHeaderData(totalDataLength: Int): ByteArray {
        val totalAudioLen: ULong = totalDataLength.toULong()
        val totalDataLen: ULong = totalAudioLen + 44u
        val longSampleRate: ULong = outputSampleRate.toULong()
        val channels: ULong = 1u
        val byteRate: ULong = (16u * longSampleRate * channels) / 8u

        val headerByteArray = ByteArray(44)
        headerByteArray[0] = 'R'.code.toByte()
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
        headerByteArray[12] = 'f'.code.toByte()
        headerByteArray[13] = 'm'.code.toByte()
        headerByteArray[14] = 't'.code.toByte()
        headerByteArray[15] = ' '.code.toByte()
        headerByteArray[16] = 16
        headerByteArray[17] = 0
        headerByteArray[18] = 0
        headerByteArray[19] = 0
        headerByteArray[20] = 1
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
        headerByteArray[32] = (16u * 1u / 8u).toByte()
        headerByteArray[33] = 0
        headerByteArray[34] = 16
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
    private fun ByteArray.toNSData() = this.usePinned {
        NSData.create(bytes = it.addressOf(0), length = this.size.convert())
    }

    inner class AVAudioPlayerDelegate : NSObject(), AVAudioPlayerDelegateProtocol {
        override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
            if (successfully) {
                onPlayEnded()
            } else {
                onPlayFailed(GgwaveException("Audio playback did not finish successfully"))
            }
        }

        override fun audioPlayerDecodeErrorDidOccur(player: AVAudioPlayer, error: NSError?) {
            onPlayFailed(GgwaveException(error?.localizedDescription ?: "Audio decode error"))
        }
    }
}

internal actual fun createGgwaveRuntime(config: GgwaveConfig): GgwaveRuntime {
    return IosGgwaveRuntime(config)
}
