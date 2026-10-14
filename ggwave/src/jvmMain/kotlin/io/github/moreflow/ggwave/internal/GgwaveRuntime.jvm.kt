package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveConfig
import io.github.moreflow.ggwave.GgwaveCodecConfig
import io.github.moreflow.ggwave.GgwaveException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import javax.sound.sampled.LineListener
import javax.sound.sampled.TargetDataLine

internal class JvmGgwaveRuntime(
    private val config: GgwaveConfig,
    private val clipFactory: () -> Clip = AudioSystem::getClip,
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

    private var clip: Clip? = null
    private var captureLine: TargetDataLine? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    override fun setListeners(
        onReceivedMessage: (String) -> Unit,
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
        val audioFormat = AudioFormat(
            config.sampleRate.toFloat(),
            16,
            1,
            true,
            true,
        )
        val line = AudioSystem.getTargetDataLine(audioFormat)
        try {
            line.open(audioFormat)
            line.start()
        } catch (error: Throwable) {
            closeCaptureLine(line)
            throw error
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val buffer = ByteArray(config.captureBufferSize)
                while (isActive) {
                    val read = line.read(buffer, 0, buffer.size)
                    if (!isActive || read <= 0) continue
                    val shortArray = ShortArray(read / 2)
                    ByteBuffer.wrap(buffer, 0, read).order(ByteOrder.BIG_ENDIAN).asShortBuffer()[shortArray]
                    val payload = synchronized(decoderLock) {
                        codecs.decoder.decode(shortArray)
                    } ?: continue
                    val text = payload.toGgwavePayload()
                    if (text.isNotBlank()) {
                        onReceivedMessage(text)
                    }
                }
            } catch (error: Throwable) {
                if (isActive) {
                    onCaptureFailed(error)
                }
            } finally {
                val ownsLine = synchronized(resourceLock) {
                    if (captureLine === line) {
                        captureLine = null
                        true
                    } else {
                        false
                    }
                }
                if (ownsLine) {
                    closeCaptureLine(line)
                }
                synchronized(resourceLock) {
                    if (captureJob === coroutineContext[Job]) {
                        captureJob = null
                    }
                }
            }
        }
        synchronized(resourceLock) {
            captureLine = line
            captureJob = job
        }
        job.start()
    }

    override fun stopCapturing() {
        val (job, line) = synchronized(resourceLock) {
            val resources = captureJob to captureLine
            captureJob = null
            captureLine = null
            resources
        }
        job?.cancel()
        closeCaptureLine(line)
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
        val (job, currentClip) = synchronized(resourceLock) {
            val resources = playbackJob to clip
            playbackJob = null
            clip = null
            resources
        }
        job?.cancel()
        closeClip(currentClip)
    }

    private fun play(samples: ShortArray) {
        stopPlayback()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var newClip: Clip? = null
            var clipRegistered = false
            try {
                val byteBuf: ByteBuffer = ByteBuffer.allocate(2 * samples.size)
                for (sample in samples) {
                    byteBuf.putShort(sample)
                }
                val byteArray = byteBuf.array()
                val audioFormat = AudioFormat(
                    config.sampleRate.toFloat(),
                    16,
                    1,
                    true,
                    true,
                )
                val createdClip = clipFactory()
                newClip = createdClip
                coroutineContext.ensureActive()
                synchronized(resourceLock) {
                    clip = createdClip
                    clipRegistered = true
                }
                createdClip.open(audioFormat, byteArray, 0, byteArray.size)
                coroutineContext.ensureActive()
                awaitPlaybackEnd(createdClip)
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
                val ownsClip = synchronized(resourceLock) {
                    if (clip === newClip) {
                        clip = null
                        true
                    } else {
                        false
                    }
                }
                if (ownsClip || !clipRegistered) {
                    closeClip(newClip)
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

    private fun closeCaptureLine(line: TargetDataLine?) {
        if (line == null) return
        try {
            line.stop()
        } catch (_: Exception) {
        }
        line.close()
    }

    private fun closeClip(clip: Clip?) {
        if (clip == null) return
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
