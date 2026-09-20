package io.github.moreflow.ggwave.internal

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import io.github.moreflow.ggwave.GgwaveConfig
import io.github.moreflow.ggwave.GgwaveException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

internal class AndroidGgwaveRuntime(
    private val config: GgwaveConfig,
) : GgwaveRuntime {
    private val ggWave: GGWave = createGGWave()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var onReceivedMessage: (String) -> Unit = {}
    private var onPlayEnded: () -> Unit = {}
    private var onPlayFailed: (Throwable) -> Unit = {}

    private var playbackTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    init {
        ggWave.init(config)
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

    override fun startCapturing() {
        stopCapturing()
        val record = createAudioRecord()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw GgwaveException("Microphone capture is unavailable. Grant RECORD_AUDIO and try again.")
        }
        audioRecord = record
        captureJob = scope.launch {
            try {
                val decodedDataArray = ShortArray(config.captureBufferSize / 2)
                record.startRecording()
                while (isActive) {
                    record.read(decodedDataArray, 0, decodedDataArray.size)
                    if (!isActive) break
                    val payload = ggWave.decode(decodedDataArray) ?: continue
                    val text = payload.toGgwavePayload()
                    if (text.isNotBlank()) {
                        onReceivedMessage(text)
                    }
                }
            } finally {
                try {
                    record.stop()
                } catch (_: Exception) {
                }
                record.release()
                if (audioRecord === record) {
                    audioRecord = null
                }
            }
        }
    }

    override fun stopCapturing() {
        val job = captureJob
        captureJob = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
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
        playbackJob?.cancel()
        playbackJob = null
        releasePlaybackTrack()
    }

    private fun play(samples: ShortArray) {
        stopPlayback()
        playbackJob = scope.launch {
            val track = try {
                createPlaybackTrack()
            } catch (error: Throwable) {
                onPlayFailed(error)
                return@launch
            }
            playbackTrack = track
            try {
                track.setVolume(AudioTrack.getMaxVolume())
                track.play()
                var offset = 0
                while (isActive && offset < samples.size) {
                    val written = track.write(
                        samples,
                        offset,
                        samples.size - offset,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    if (written <= 0) {
                        onPlayFailed(GgwaveException("AudioTrack write failed: $written"))
                        return@launch
                    }
                    offset += written
                }
                while (isActive && track.playState == AudioTrack.PLAYSTATE_PLAYING && track.playbackHeadPosition < samples.size) {
                    delay(20)
                }
                if (isActive) {
                    onPlayEnded()
                }
            } catch (error: Throwable) {
                if (isActive) {
                    onPlayFailed(error)
                }
            } finally {
                releasePlaybackTrack()
            }
        }
    }

    private fun createPlaybackTrack(): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            throw GgwaveException("AudioTrack is not supported for sample rate ${config.sampleRate}")
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(config.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBufferSize, 16 * 1024))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw GgwaveException("AudioTrack failed to initialize")
        }
        return track
    }

    private fun releasePlaybackTrack() {
        val track = playbackTrack
        playbackTrack = null
        if (track == null) return
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
                track.flush()
                track.stop()
            }
        } catch (_: Exception) {
        }
        track.release()
    }

    override fun close() {
        stopCapturing()
        stopPlayback()
        ggWave.close()
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        return AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            config.captureBufferSize,
        )
    }
}

internal actual fun createGgwaveRuntime(config: GgwaveConfig): GgwaveRuntime {
    return AndroidGgwaveRuntime(config)
}
