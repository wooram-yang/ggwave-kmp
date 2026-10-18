package io.github.moreflow.ggwave.internal

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import io.github.moreflow.ggwave.GgwaveConfig
import io.github.moreflow.ggwave.GgwaveException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max

internal class AndroidGgwaveRuntime(
    config: GgwaveConfig,
) : BaseGgwaveRuntime(config) {
    private val captureStream = ActiveStream(::releaseAudioRecord)
    private val playbackStream = ActiveStream(::releasePlaybackTrack)

    override fun startCapturing() {
        stopCapturing()
        val bufferSize = resolveCaptureBufferSize()
        val record = createAudioRecord(bufferSize)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw GgwaveException("Microphone capture is unavailable. Grant RECORD_AUDIO and try again.")
        }
        captureStream.start(scope, resource = record, onFailure = { listener.onCaptureFailed(it) }) {
            val buffer = ShortArray(bufferSize / Short.SIZE_BYTES)
            val decoder = CaptureDecoder(codecs, listener)
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw GgwaveException("AudioRecord failed to start recording")
            }
            while (isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (!isActive) break
                if (read < 0) {
                    throw GgwaveException("AudioRecord read failed: $read")
                }
                if (read == 0) continue
                decoder.decode(if (read == buffer.size) buffer else buffer.copyOf(read))
            }
        }
    }

    override fun stopCapturing() {
        captureStream.stop()
    }

    override fun startPlayback(message: String) {
        stopPlayback()
        playbackStream.start(scope, onFailure = { listener.onPlayFailed(it) }) {
            val samples = codecs.encode(message, config.protocol, config.volume)
            val track = createPlaybackTrack()
            playbackStream.attach(track)
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
                    throw GgwaveException("AudioTrack write failed: $written")
                }
                offset += written
            }
            while (isActive && track.playState == AudioTrack.PLAYSTATE_PLAYING && track.playbackHeadPosition < samples.size) {
                delay(20)
            }
            if (isActive) {
                listener.onPlayEnded()
            }
        }
    }

    override fun stopPlayback() {
        playbackStream.stop()
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

    private fun releasePlaybackTrack(track: AudioTrack) {
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

    private fun releaseAudioRecord(record: AudioRecord) {
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
