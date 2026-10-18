package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import javax.sound.sampled.LineListener
import javax.sound.sampled.TargetDataLine

internal class JvmGgwaveRuntime(
    config: GgwaveConfig,
    private val clipFactory: () -> Clip = AudioSystem::getClip,
) : BaseGgwaveRuntime(config) {
    private val audioFormat = AudioFormat(config.sampleRate.toFloat(), 16, 1, true, false)
    private val captureStream = ActiveStream(::closeCaptureLine)
    private val playbackStream = ActiveStream(::closeClip)

    override fun startCapturing() {
        stopCapturing()
        val line = AudioSystem.getTargetDataLine(audioFormat)
        try {
            line.open(audioFormat)
            line.start()
        } catch (error: Throwable) {
            closeCaptureLine(line)
            throw error
        }
        captureStream.start(scope, resource = line, onFailure = { listener.onCaptureFailed(it) }) {
            val buffer = ByteArray(config.captureBufferSize)
            val decoder = CaptureDecoder(codecs, listener)
            while (isActive) {
                val read = line.read(buffer, 0, buffer.size)
                if (!isActive || read <= 0) continue
                decoder.decode(buffer.toLittleEndianShortArray(read))
            }
        }
    }

    override fun stopCapturing() {
        captureStream.stop()
    }

    override fun startPlayback(message: String) {
        stopPlayback()
        playbackStream.start(scope, onFailure = { listener.onPlayFailed(it) }) {
            val audioData = codecs.encode(message, config.protocol, config.volume).toLittleEndianByteArray()
            val clip = clipFactory()
            playbackStream.attach(clip)
            clip.open(audioFormat, audioData, 0, audioData.size)
            ensureActive()
            awaitPlaybackEnd(clip)
            if (isActive) {
                listener.onPlayEnded()
            }
        }
    }

    override fun stopPlayback() {
        playbackStream.stop()
    }

    private suspend fun awaitPlaybackEnd(clip: Clip) {
        val playbackEnded = CompletableDeferred<Unit>()
        val listener = LineListener { event ->
            if (event.type == LineEvent.Type.STOP) {
                playbackEnded.complete(Unit)
            }
        }
        clip.addLineListener(listener)
        try {
            clip.start()
            playbackEnded.await()
        } finally {
            clip.removeLineListener(listener)
        }
    }

    private fun closeCaptureLine(line: TargetDataLine) {
        try {
            line.stop()
        } catch (_: Exception) {
        }
        line.close()
    }

    private fun closeClip(clip: Clip) {
        try {
            clip.stop()
            clip.flush()
        } catch (_: Exception) {
        }
        clip.close()
    }
}

internal actual fun createGgwaveRuntime(config: GgwaveConfig): GgwaveRuntime {
    return JvmGgwaveRuntime(config)
}
