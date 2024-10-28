package com.example.ggwave_multiplatform

import CaptureSoundListener
import PlaySoundListener
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSMutableData
import platform.Foundation.appendData
import platform.Foundation.create
import platform.darwin.NSObject
import kotlin.experimental.and


object IOSCoreManager: BaseCoreManager {
    private const val SAMPLE_RATE = 48000

    override var ggWave: GGWave = GGWaveFactory.createInstance()
    override lateinit var playSoundListener: PlaySoundListener
    override lateinit var captureSoundListener: CaptureSoundListener

    override var messageWillBeSent: String = ""

    private val scope = CoroutineScope(Job() + Dispatchers.Default)

    private var encodedDataArray: ShortArray? = null
    private var audioPlayer: AVAudioPlayer? = null

    private var willStopRecording = false


    init {
        ggWave.delegate = this
        ggWave.initNative()

        initAudioRecord()
    }

    override fun startCapturing() {
        capture()
    }

    override fun stopCapturing() {
        willStopRecording = true
    }

    override fun capture() {
        scope.launch {
            while (true) {
                if(willStopRecording) {
                    break
                }
            }

            willStopRecording = false
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

    override fun playing() {
    }

    override fun onNativeReceivedMessage(data: ByteArray) {
        captureSoundListener.onReceivedMessage(data.toString())
    }

    override fun onNativeMessageEncoded(data: ShortArray) {
        encodedDataArray = data
        play()
    }

    private fun initAudioRecord() {
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
        val longSampleRate: ULong = SAMPLE_RATE.toULong()
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