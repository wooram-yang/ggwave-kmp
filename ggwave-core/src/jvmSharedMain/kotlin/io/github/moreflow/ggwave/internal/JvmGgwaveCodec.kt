package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveCodecConfig
import io.github.moreflow.ggwave.GgwaveProtocol

internal class JvmGgwaveCodec(config: GgwaveCodecConfig) : NativeGgwaveCodec() {
    init {
        open(initNative(config.inputSampleRate, config.outputSampleRate))
    }

    override fun nativeEncode(handle: Long, payload: ByteArray, protocol: GgwaveProtocol, volume: Int): ShortArray? =
        encodeNative(handle, payload, protocol.nativeId, volume)

    override fun nativeDecode(handle: Long, samples: ShortArray): ByteArray? = decodeNative(handle, samples)

    override fun nativeRelease(handle: Long) = releaseNative(handle)

    // Symbol names are fixed by ggwave_jni.cpp and kept by consumer-rules.pro.
    private external fun initNative(inputSampleRate: Int, outputSampleRate: Int): Long
    private external fun releaseNative(handle: Long)
    private external fun decodeNative(handle: Long, samples: ShortArray): ByteArray?
    private external fun encodeNative(
        handle: Long,
        payload: ByteArray,
        protocolId: Int,
        volume: Int,
    ): ShortArray?
}
