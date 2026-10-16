package io.github.moreflow.ggwave.internal

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import io.github.moreflow.ggwave.GgwaveConfig
import io.github.moreflow.ggwave.GgwaveCodecConfig
import io.github.moreflow.ggwave.GgwaveException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

internal class AndroidGgwaveRuntime(
    private val config: GgwaveConfig,
) : GgwaveRuntime {
    private val codecs = GgwaveRuntimeCodecs.create(
        GgwaveCodecConfig(config.sampleRate, config.sampleRate),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val resourceLock = Any()
    private val encoderLock = Any()
    private val decoderLock = Any()

    private var onReceivedMessage: (String) -> Unit = {}
    private var onCaptureFailed: (Throwable) -> Unit = {}
    private var onPlayEnded: () -> Unit = {}
    private var onPlayFailed: (Throwable) -> Unit = {}

    private var playbackTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    override fun setListeners(
        onReceivedMessage: (String) -> Unit,
        onCaptureLevelChanged: (Float) -> Unit,
        onCaptureFailed: (Throwable) -> Unit,
        onPlayEnded: () -> Unit,
        onPlayFailed: (Throwable) -> Unit,
    ) {
        this.onReceivedMessage = onReceivedMessage
        this.onCaptureFailed = onCaptureFailed
        this.onPlayEnded = onPlayEnded
        this.onPlayFailed = onPlayFailed
    }

    override fun startCapturing() {
        stopCapturing()
        val bufferSize = resolveCaptureBufferSize()
        val record = createAudioRecord(bufferSize)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw GgwaveException("Microphone capture is unavailable. Grant RECORD_AUDIO and try again.")
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val decodedDataArray = ShortArray(bufferSize / Short.SIZE_BYTES)
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw GgwaveException("AudioRecord failed to start recording")
                }
                while (isActive) {
                    val read = record.read(decodedDataArray, 0, decodedDataArray.size)
                    if (!isActive) break
                    if (read < 0) {
                        throw GgwaveException("AudioRecord read failed: $read")
                    }
                    if (read == 0) continue
                    val samples = if (read == decodedDataArray.size) {
                        decodedDataArray
                    } else {
                        decodedDataArray.copyOf(read)
                    }
                    val payload = synchronized(decoderLock) {
                        codecs.decoder.decode(samples)
                    } ?: continue
                    val text = payload.toGgwavePayload()
                    if (text.isNotBlank()) {
                        onReceivedMessage(text)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isActive) {
                    onCaptureFailed(error)
                }
            } finally {
                val ownsRecord = synchronized(resourceLock) {
                    if (audioRecord === record) {
                        audioRecord = null
                        true
                    } else {
                        false
                    }
                }
                if (ownsRecord) {
                    releaseAudioRecord(record)
                }
                synchronized(resourceLock) {
                    if (captureJob === coroutineContext[Job]) {
                        captureJob = null
                    }
                }
            }
        }
        synchronized(resourceLock) {
            audioRecord = record
            captureJob = job
        }
        job.start()
    }

    override fun stopCapturing() {
        val (job, record) = synchronized(resourceLock) {
            val resources = captureJob to audioRecord
            captureJob = null
            audioRecord = null
            resources
        }
        job?.cancel()
        releaseAudioRecord(record)
    }

    override fun startPlayback(message: String) {
        val encoded = try {
            synchronized(encoderLock) {
                codecs.encoder.encode(message.encodeToByteArray(), volume = config.volume)
            }
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
        val (job, track) = synchronized(resourceLock) {
            val resources = playbackJob to playbackTrack
            playbackJob = null
            playbackTrack = null
            resources
        }
        job?.cancel()
        releasePlaybackTrack(track)
    }

    private fun play(samples: ShortArray) {
        stopPlayback()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var track: AudioTrack? = null
            var trackRegistered = false
            try {
                val createdTrack = createPlaybackTrack()
                track = createdTrack
                coroutineContext.ensureActive()
                synchronized(resourceLock) {
                    playbackTrack = createdTrack
                    trackRegistered = true
                }
                coroutineContext.ensureActive()
                createdTrack.setVolume(AudioTrack.getMaxVolume())
                createdTrack.play()
                var offset = 0
                while (isActive && offset < samples.size) {
                    val written = createdTrack.write(
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
                while (isActive && createdTrack.playState == AudioTrack.PLAYSTATE_PLAYING && createdTrack.playbackHeadPosition < samples.size) {
                    delay(20)
                }
                if (isActive) {
                    onPlayEnded()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isActive) {
                    onPlayFailed(error)
                }
            } finally {
                val ownsTrack = synchronized(resourceLock) {
                    if (playbackTrack === track) {
                        playbackTrack = null
                        true
                    } else {
                        false
                    }
                }
                if (ownsTrack || !trackRegistered) {
                    releasePlaybackTrack(track)
                }
                synchronized(resourceLock) {
                    if (playbackJob === coroutineContext[Job]) {
                        playbackJob = null
                    }
                }
            }
        }
        synchronized(resourceLock) {
            playbackJob = job
        }
        job.start()
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

    private fun releasePlaybackTrack(track: AudioTrack?) {
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
        scope.cancel()
        try {
            synchronized(encoderLock) {
                codecs.encoder.close()
            }
        } finally {
            synchronized(decoderLock) {
                codecs.decoder.close()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(bufferSize: Int): AudioRecord {
        return AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
    }

    private fun resolveCaptureBufferSize(): Int {
        val minBufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            throw GgwaveException("AudioRecord is not supported for sample rate ${config.sampleRate}")
        }
        return max(config.captureBufferSize, minBufferSize)
    }

    private fun releaseAudioRecord(record: AudioRecord?) {
        if (record == null) return
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (_: Exception) {
        }
        record.release()
    }
}

internal actual fun createGgwaveRuntime(config: GgwaveConfig): GgwaveRuntime {
    return AndroidGgwaveRuntime(config)
}
