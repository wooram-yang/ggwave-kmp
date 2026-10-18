package io.github.moreflow.ggwave.internal

import ggwave.ggwave_SampleFormat
import ggwave.ggwave_TxProtocolId
import ggwave.ggwave_decode
import ggwave.ggwave_encode
import ggwave.ggwave_free
import ggwave.ggwave_getDefaultParameters
import ggwave.ggwave_init
import io.github.moreflow.ggwave.GgwaveCodecConfig
import io.github.moreflow.ggwave.GgwaveProtocol
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.copy
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

@OptIn(ExperimentalForeignApi::class)
internal class IosGgwaveCodec(config: GgwaveCodecConfig) : NativeGgwaveCodec() {
    init {
        val parameters = ggwave_getDefaultParameters().copy {
            sampleFormatInp = ggwave_SampleFormat.GGWAVE_SAMPLE_FORMAT_I16
            sampleFormatOut = ggwave_SampleFormat.GGWAVE_SAMPLE_FORMAT_I16
            sampleRateInp = config.inputSampleRate.toFloat()
            sampleRateOut = config.outputSampleRate.toFloat()
        }
        open(ggwave_init(parameters).toLong())
    }

    override fun nativeEncode(handle: Long, payload: ByteArray, protocol: GgwaveProtocol, volume: Int): ShortArray? {
        val instance = handle.toInt()
        val nativeProtocol = ggwave_TxProtocolId.byValue(protocol.nativeId.toUInt())
        val byteCount = ggwave_encode(instance, payload.refTo(0), payload.size, nativeProtocol, volume, null, 1)
        if (byteCount <= 0 || byteCount % Short.SIZE_BYTES != 0) return null

        val waveform = ShortArray(byteCount / Short.SIZE_BYTES)
        val sampleCount = waveform.usePinned { pinned ->
            ggwave_encode(
                instance,
                payload.refTo(0),
                payload.size,
                nativeProtocol,
                volume,
                pinned.addressOf(0).reinterpret<ByteVar>(),
                0,
            )
        }
        if (sampleCount <= 0 || sampleCount > waveform.size) return null
        return if (sampleCount == waveform.size) waveform else waveform.copyOf(sampleCount)
    }

    override fun nativeDecode(handle: Long, samples: ShortArray): ByteArray? {
        val output = ByteArray(DECODE_BUFFER_SIZE)
        val decodedCount = samples.usePinned { pinned ->
            ggwave_decode(
                handle.toInt(),
                pinned.addressOf(0).reinterpret<ByteVar>(),
                samples.size * Short.SIZE_BYTES,
                output.refTo(0),
            )
        }
        return if (decodedCount > 0) output.copyOf(decodedCount) else null
    }

    override fun nativeRelease(handle: Long) {
        ggwave_free(handle.toInt())
    }

    private companion object {
        const val DECODE_BUFFER_SIZE = 256
    }
}
