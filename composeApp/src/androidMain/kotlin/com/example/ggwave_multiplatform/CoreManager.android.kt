package com.example.ggwave_multiplatform

import CaptureSoundListener
import PlaySoundListener
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioTrack.OnPlaybackPositionUpdateListener
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object AndroidCoreManager: BaseCoreManager {
    override var ggWave: GGWave = GGWaveFactory.createInstance()
    override lateinit var playSoundListener: PlaySoundListener
    override lateinit var captureSoundListener: CaptureSoundListener

    override var messageWillBeSent: String = ""

    init {
        ggWave.delegate = this
        ggWave.initNative()

        initAudioTrack()
        initAudioRecord()
    }

    private val scope = CoroutineScope(Job() + Dispatchers.Default)

    private const val sampleHz = 48000
    private var encodedDataArray: ShortArray? = null
    private lateinit var audioTrack: AudioTrack

    private const val recordingBufferSize = 4 * 1024
    private var willStopRecording = false
    private var decodedDataArray: ShortArray = ShortArray(recordingBufferSize / 2)
    private lateinit var audioRecord: AudioRecord


    override fun startCapturing() {
        capture()
    }

    override fun stopCapturing() {
        willStopRecording = true
    }

    @SuppressLint("MissingPermission")
    override fun capture() {
        scope.launch {
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.d("CoreManager", "AudioRecord couldn't initialize")
                return@launch
            }

            decodedDataArray = ShortArray(recordingBufferSize / 2)

            audioRecord.startRecording()
            var totalRead = 0
            while (true) {
                if(willStopRecording) {
                    break
                }

                val offsetRecording = audioRecord.read(decodedDataArray, 0, decodedDataArray.size)
                totalRead += offsetRecording
                ggWave.processCaptureData(shortData = decodedDataArray)
            }

            audioRecord.stop()

            willStopRecording = false
        }
    }

    override fun startPlayback() {
        ggWave.sendMessage(messageWillBeSent)
    }

    override fun stopPlayback() {
        audioTrack.stop()
    }

    override fun play() {
        scope.launch {
            encodedDataArray?.let {
                audioTrack.setPlaybackPositionUpdateListener(
                    object: OnPlaybackPositionUpdateListener {
                        override fun onMarkerReached(track: AudioTrack?) {
                            track?.stop()
                            playSoundListener.onPlayEnded()
                        }

                        override fun onPeriodicNotification(track: AudioTrack?) {
                        }

                    }
                )
                audioTrack.setPositionNotificationPeriod(sampleHz / 30)
                audioTrack.setNotificationMarkerPosition(it.size)

                if(audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.play()
                    audioTrack.write(it, 0, it.size)
                }
            }
        }
    }

    override fun playing() {}

    override fun onNativeReceivedMessage(data: ByteArray) {
        val resultString = String(data)

        Log.v("CoreManager", "onNativeReceivedMessage: $resultString")

        captureSoundListener.onReceivedMessage(resultString)
    }

    override fun onNativeMessageEncoded(data: ShortArray) {
        Log.v("CoreManager", "onNativeMessageEncoded: ${data.toString()}")

        encodedDataArray = data
        play()
    }

    private fun initAudioTrack() {
        audioTrack = AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(16 * 1024)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun initAudioRecord() {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            sampleHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            recordingBufferSize
        )
    }
}

actual object CoreManagerFactory {
    actual fun createInstance(): BaseCoreManager = AndroidCoreManager
}