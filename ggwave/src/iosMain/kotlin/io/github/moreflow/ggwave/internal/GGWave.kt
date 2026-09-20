package io.github.moreflow.ggwave.internal

import ggwave.ggwave_Instance
import ggwave.ggwave_SampleFormat
import ggwave.ggwave_TxProtocolId
import ggwave.ggwave_decode
import ggwave.ggwave_encode
import ggwave.ggwave_free
import ggwave.ggwave_getDefaultParameters
import ggwave.ggwave_init
import io.github.moreflow.ggwave.GgwaveConfig
import io.github.moreflow.ggwave.GgwaveException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.copy
import kotlinx.cinterop.refTo
import kotlinx.cinterop.usePinned

@OptIn(ExperimentalForeignApi::class)
internal class IOSGGWave : GGWave {
    private var ggwaveInstance: ggwave_Instance = -1
    private val inputSampleRate = 44100f

    override fun init(config: GgwaveConfig) {
        close()
        var ggwaveParams = ggwave_getDefaultParameters()
        ggwaveParams = ggwaveParams.copy {
            sampleFormatInp = ggwave_SampleFormat.GGWAVE_SAMPLE_FORMAT_I16
            sampleFormatOut = ggwave_SampleFormat.GGWAVE_SAMPLE_FORMAT_I16
            sampleRateInp = inputSampleRate
            sampleRateOut = config.sampleRate.toFloat()
        }
        ggwaveInstance = ggwave_init(ggwaveParams)
    }

    override fun close() {
        if (ggwaveInstance >= 0) {
            ggwave_free(ggwaveInstance)
            ggwaveInstance = -1
        }
    }

    override fun decode(shortData: ShortArray): ByteArray? = null

    override fun decode(byteData: ByteArray): ByteArray? {
        if (ggwaveInstance < 0 || byteData.isEmpty()) return null
        val output = ByteArray(256)
        val decodeCount = ggwave_decode(
            ggwaveInstance,
            byteData.refTo(0),
            byteData.size,
            output.refTo(0),
        )
        if (decodeCount <= 0) return null
        return output.copyOf(decodeCount)
    }

    override fun encode(message: String, volume: Int): ShortArray {
        if (ggwaveInstance < 0) {
            throw GgwaveException("ggwave instance is not initialized")
        }
        val payload = message.encodeToByteArray()
        val n: Int = ggwave_encode(
            ggwaveInstance,
            message,
            payload.size,
            ggwave_TxProtocolId.GGWAVE_TX_PROTOCOL_AUDIBLE_FAST,
            volume,
            null,
            1,
        )
        if (n <= 0) {
            throw GgwaveException("Failed to encode message")
        }

        val waveform = ByteArray(n)
        val encodedCount = waveform.usePinned {
            ggwave_encode(
                ggwaveInstance,
                message,
                payload.size,
                ggwave_TxProtocolId.GGWAVE_TX_PROTOCOL_AUDIBLE_FAST,
                volume,
                it.addressOf(0),
                0,
            )
        }
        if (encodedCount <= 0) {
            throw GgwaveException("Failed to encode message")
        }

        return ShortArray(encodedCount) {
            (waveform[it * 2] + (waveform[(it * 2) + 1].toInt() shl 8)).toShort()
        }
    }
}

internal actual fun createGGWave(): GGWave = IOSGGWave()
