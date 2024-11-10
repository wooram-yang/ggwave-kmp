package com.example.ggwavekmp

import CaptureSoundListener
import PlaySoundListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent


object JVMCoreManager: BaseCoreManager {
    override var ggWave: GGWave = GGWaveFactory.createInstance()
    override lateinit var playSoundListener: PlaySoundListener
    override lateinit var captureSoundListener: CaptureSoundListener

    override var messageWillBeSent: String = ""

    init {
        ggWave.delegate = this
        ggWave.initNative()
    }

    private val scope = CoroutineScope(Job() + Dispatchers.Default)

    private const val sampleHz = 48000f
    private var encodedDataArray: ShortArray? = null
    private lateinit var clip: Clip

    private const val recordingBufferSize = 4 * 1024
    private var willStopRecording = false


    override fun startCapturing() {
        capture()
    }

    override fun stopCapturing() {
        willStopRecording = true
    }

    override fun capture() {
        scope.launch {
            val audioFormat = AudioFormat(
                sampleHz,
                16,
                1,
                true,
                true)

            val recordObject = AudioSystem.getTargetDataLine(audioFormat)
            recordObject.open(audioFormat)
            recordObject.start()

            val byteBuf: ByteBuffer = ByteBuffer.allocate(recordingBufferSize)
            val buffer = byteBuf.array()

            var totalRead = 0
            while (true) {
                if(willStopRecording) {
                    break
                }

                val byteRead = recordObject.read(buffer, 0, buffer.size)
                totalRead += byteRead

                val shortArray = ShortArray(buffer.size / 2)
                ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN).asShortBuffer()[shortArray]

                ggWave.processCaptureData(shortData = shortArray)
            }
            recordObject.close()

            willStopRecording = false
        }
    }

    override fun startPlayback() {
        ggWave.sendMessage(messageWillBeSent)
    }

    override fun stopPlayback() {
        clip.stop()
    }

    override fun play() {
        scope.launch {
            encodedDataArray?.let { it ->
                val byteBuf: ByteBuffer = ByteBuffer.allocate(2 * it.size)
                for(i in it) {
                    byteBuf.putShort(i)
                }
                val byteArray = byteBuf.array()

                val audioFormat = AudioFormat(
                    sampleHz,
                    16,
                    1,
                    true,
                    true)

                clip = AudioSystem.getClip()
                clip.addLineListener { event ->
                    if(event.type == LineEvent.Type.STOP) {
                        playSoundListener.onPlayEnded()
                    }
                }
                clip.open(audioFormat, byteArray, 0 , byteArray.size)
                clip.start()
                clip.drain()
            }
        }
    }

    override fun playing() {}

    override fun onNativeReceivedMessage(data: ByteArray) {
        val resultString = String(data)

        print("onNativeReceivedMessage: $resultString\n")

        captureSoundListener.onReceivedMessage(resultString)
    }

    override fun onNativeMessageEncoded(data: ShortArray) {
        print("onNativeMessageEncoded: $data\n")

        encodedDataArray = data
        play()
    }
}

actual object CoreManagerFactory {
    actual fun createInstance(): BaseCoreManager = JVMCoreManager
}