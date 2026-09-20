package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveConfig
import io.github.moreflow.ggwave.GgwaveException

internal class JVMGGWave : GGWave {
    override fun init(config: GgwaveConfig) {
        initNative(config.sampleRate)
    }

    override fun close() {
        releaseNative()
    }

    override fun decode(shortData: ShortArray): ByteArray? = decodeNative(shortData)

    override fun decode(byteData: ByteArray): ByteArray? = null

    override fun encode(message: String, volume: Int): ShortArray {
        return encodeNative(message, volume)
            ?: throw GgwaveException("Failed to encode message")
    }

    private external fun initNative(sampleRate: Int)
    private external fun releaseNative()
    private external fun decodeNative(shortData: ShortArray): ByteArray?
    private external fun encodeNative(message: String, volume: Int): ShortArray?
}
