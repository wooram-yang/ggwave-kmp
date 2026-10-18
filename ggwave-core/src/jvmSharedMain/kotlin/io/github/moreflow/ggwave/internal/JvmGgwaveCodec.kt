package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveCodec
import io.github.moreflow.ggwave.GgwaveCodecConfig
import io.github.moreflow.ggwave.GgwaveException
import io.github.moreflow.ggwave.GgwaveProtocol

internal class JvmGgwaveCodec(config: GgwaveCodecConfig) : GgwaveCodec {
    private var nativeHandle = initNative(config.inputSampleRate, config.outputSampleRate)

    init {
        if (nativeHandle < 0) throw GgwaveException("Failed to initialize ggwave")
    }

    override fun encode(payload: ByteArray, protocol: GgwaveProtocol, volume: Int): ShortArray {
        requireValidEncodeArguments(payload, volume)
        return encodeNative(requireHandle(), payload, protocol.nativeId, volume)
            ?: throw GgwaveException("Failed to encode payload")
    }

    override fun decode(samples: ShortArray): ByteArray? {
        if (samples.isEmpty()) return null
        return decodeNative(requireHandle(), samples)
    }

    override fun close() {
        if (nativeHandle < 0) return
        releaseNative(nativeHandle)
        nativeHandle = CLOSED_HANDLE
    }

    private fun requireHandle(): Long {
        check(nativeHandle >= 0) { "GgwaveCodec is closed" }
        return nativeHandle
    }

    private external fun initNative(inputSampleRate: Int, outputSampleRate: Int): Long
    private external fun releaseNative(handle: Long)
    private external fun decodeNative(handle: Long, samples: ShortArray): ByteArray?
    private external fun encodeNative(
        handle: Long,
        payload: ByteArray,
        protocolId: Int,
        volume: Int,
    ): ShortArray?

    companion object {
        private const val CLOSED_HANDLE = -1L
    }
}
