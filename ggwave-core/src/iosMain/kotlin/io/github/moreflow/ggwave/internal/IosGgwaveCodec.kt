package io.github.moreflow.ggwave.internal

import ggwave.ggwave_Instance
import ggwave.ggwave_SampleFormat
import ggwave.ggwave_TxProtocolId
import ggwave.ggwave_decode
import ggwave.ggwave_encode
import ggwave.ggwave_free
import ggwave.ggwave_getDefaultParameters
import ggwave.ggwave_init
import io.github.moreflow.ggwave.GgwaveCodec
import io.github.moreflow.ggwave.GgwaveCodecConfig
import io.github.moreflow.ggwave.GgwaveException
import io.github.moreflow.ggwave.GgwaveProtocol
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.copy
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

@OptIn(ExperimentalForeignApi::class)
internal class IosGgwaveCodec(config: GgwaveCodecConfig) : GgwaveCodec {
    private var nativeHandle: ggwave_Instance = createNativeHandle(config)

    init {
        if (nativeHandle < 0) throw GgwaveException("Failed to initialize ggwave")
    }

    override fun encode(payload: ByteArray, protocol: GgwaveProtocol, volume: Int): ShortArray {
        require(payload.isNotEmpty()) { "payload must not be empty" }
        require(payload.size <= GgwaveCodec.MAX_PAYLOAD_SIZE) {
            "payload must not exceed ${GgwaveCodec.MAX_PAYLOAD_SIZE} bytes"
        }
        require(volume in 0..100) { "volume must be between 0 and 100" }
        val handle = requireHandle()
        val nativeProtocol = protocol.toNativeProtocol()
        val byteCount = ggwave_encode(
            handle,
            payload.refTo(0),
            payload.size,
            nativeProtocol,
            volume,
            null,
            1,
        )
        if (byteCount <= 0) throw GgwaveException("Failed to encode payload")

        val waveform = ByteArray(byteCount)
        val sampleCount = waveform.usePinned { pinned ->
            ggwave_encode(
                handle,
                payload.refTo(0),
                payload.size,
                nativeProtocol,
                volume,
                pinned.addressOf(0),
                0,
            )
        }
        if (sampleCount <= 0 || sampleCount * 2 > waveform.size) {
            throw GgwaveException("Failed to encode payload")
        }

        return ShortArray(sampleCount) { index ->
            val low = waveform[index * 2].toInt() and 0xFF
            val high = waveform[(index * 2) + 1].toInt() shl 8
            (low or high).toShort()
        }
    }

    override fun decode(samples: ShortArray): ByteArray? {
        if (samples.isEmpty()) return null
        val output = ByteArray(DECODE_BUFFER_SIZE)
        val decodedCount = samples.usePinned { pinned ->
            ggwave_decode(
                requireHandle(),
                pinned.addressOf(0).reinterpret<ByteVar>(),
                samples.size * Short.SIZE_BYTES,
                output.refTo(0),
            )
        }
        return if (decodedCount > 0) output.copyOf(decodedCount) else null
    }

    override fun close() {
        if (nativeHandle < 0) return
        ggwave_free(nativeHandle)
        nativeHandle = CLOSED_HANDLE
    }

    private fun requireHandle(): ggwave_Instance {
        check(nativeHandle >= 0) { "GgwaveCodec is closed" }
        return nativeHandle
    }

    private fun createNativeHandle(config: GgwaveCodecConfig): ggwave_Instance {
        var parameters = ggwave_getDefaultParameters()
        parameters = parameters.copy {
            sampleFormatInp = ggwave_SampleFormat.GGWAVE_SAMPLE_FORMAT_I16
            sampleFormatOut = ggwave_SampleFormat.GGWAVE_SAMPLE_FORMAT_I16
            sampleRateInp = config.inputSampleRate.toFloat()
            sampleRateOut = config.outputSampleRate.toFloat()
        }
        return ggwave_init(parameters)
    }

    private fun GgwaveProtocol.toNativeProtocol(): ggwave_TxProtocolId = when (this) {
        GgwaveProtocol.AudibleNormal -> ggwave_TxProtocolId.GGWAVE_TX_PROTOCOL_AUDIBLE_NORMAL
        GgwaveProtocol.AudibleFast -> ggwave_TxProtocolId.GGWAVE_TX_PROTOCOL_AUDIBLE_FAST
        GgwaveProtocol.AudibleFastest -> ggwave_TxProtocolId.GGWAVE_TX_PROTOCOL_AUDIBLE_FASTEST
        GgwaveProtocol.UltrasoundNormal -> ggwave_TxProtocolId.GGWAVE_TX_PROTOCOL_ULTRASOUND_NORMAL
        GgwaveProtocol.UltrasoundFast -> ggwave_TxProtocolId.GGWAVE_TX_PROTOCOL_ULTRASOUND_FAST
        GgwaveProtocol.UltrasoundFastest -> ggwave_TxProtocolId.GGWAVE_TX_PROTOCOL_ULTRASOUND_FASTEST
        GgwaveProtocol.DualToneNormal -> ggwave_TxProtocolId.GGWAVE_TX_PROTOCOL_DT_NORMAL
        GgwaveProtocol.DualToneFast -> ggwave_TxProtocolId.GGWAVE_TX_PROTOCOL_DT_FAST
        GgwaveProtocol.DualToneFastest -> ggwave_TxProtocolId.GGWAVE_TX_PROTOCOL_DT_FASTEST
    }

    companion object {
        private const val CLOSED_HANDLE: ggwave_Instance = -1
        private const val DECODE_BUFFER_SIZE = 256
    }
}
