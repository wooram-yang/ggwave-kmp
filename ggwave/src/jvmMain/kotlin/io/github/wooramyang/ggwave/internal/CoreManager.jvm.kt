package io.github.wooramyang.ggwave.internal

import io.github.wooramyang.ggwave.GgwaveConfig
import io.github.wooramyang.ggwave.GgwaveException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.TargetDataLine

internal class JvmGgwaveRuntime(
    private val config: GgwaveConfig,
) : GgwaveRuntime {
    private val ggWave: GGWave = createGGWave()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var onReceivedMessage: (String) -> Unit = {}
    private var onPlayEnded: () -> Unit = {}
    private var onPlayFailed: (Throwable) -> Unit = {}

    private var clip: Clip? = null
    private var captureLine: TargetDataLine? = null
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
        val audioFormat = AudioFormat(
            config.sampleRate.toFloat(),
            16,
            1,
            true,
            true,
        )
        val line = AudioSystem.getTargetDataLine(audioFormat)
        line.open(audioFormat)
        captureLine = line
        captureJob = scope.launch {
            try {
                line.start()
                val buffer = ByteArray(config.captureBufferSize)
                while (isActive) {
                    val read = line.read(buffer, 0, buffer.size)
                    if (!isActive || read <= 0) continue
                    val shortArray = ShortArray(read / 2)
                    ByteBuffer.wrap(buffer, 0, read).order(ByteOrder.BIG_ENDIAN).asShortBuffer()[shortArray]
                    val payload = ggWave.decode(shortArray) ?: continue
                    val text = payload.toGgwavePayload()
                    if (text.isNotBlank()) {
                        onReceivedMessage(text)
                    }
                }
            } finally {
                line.stop()
                line.close()
                if (captureLine === line) {
                    captureLine = null
                }
            }
        }
    }

    override fun stopCapturing() {
        val job = captureJob
        captureJob = null
        captureLine?.stop()
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
        clip?.stop()
    }

    private fun play(samples: ShortArray) {
        scope.launch {
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
                val newClip = AudioSystem.getClip()
                clip = newClip
                newClip.open(audioFormat, byteArray, 0, byteArray.size)
                newClip.start()
                newClip.drain()
                onPlayEnded()
            } catch (error: Throwable) {
                onPlayFailed(error)
            }
        }
    }

    override fun close() {
        stopCapturing()
        stopPlayback()
        clip?.close()
        clip = null
        ggWave.close()
        scope.cancel()
    }
}

internal actual fun createGgwaveRuntime(config: GgwaveConfig): GgwaveRuntime {
    return JvmGgwaveRuntime(config)
}
