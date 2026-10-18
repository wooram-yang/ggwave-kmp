package io.github.moreflow.ggwave

import io.github.moreflow.ggwave.internal.createGgwaveCodec

/**
 * Stateful ggwave encoder/decoder.
 *
 * A codec instance represents one decode stream and is not safe for concurrent calls.
 */
interface GgwaveCodec : AutoCloseable {
    fun encode(
        payload: ByteArray,
        protocol: GgwaveProtocol = DEFAULT_PROTOCOL,
        volume: Int = DEFAULT_VOLUME,
    ): ShortArray

    fun decode(samples: ShortArray): ByteArray?

    override fun close()

    companion object {
        val DEFAULT_PROTOCOL = GgwaveProtocol.AudibleFast
        const val DEFAULT_VOLUME = 50
        const val MIN_VOLUME = 0
        const val MAX_VOLUME = 100
        const val MAX_PAYLOAD_SIZE = 140

        fun create(config: GgwaveCodecConfig = GgwaveCodecConfig()): GgwaveCodec =
            createGgwaveCodec(config)
    }
}

fun GgwaveCodec.encodeText(
    text: String,
    protocol: GgwaveProtocol = GgwaveCodec.DEFAULT_PROTOCOL,
    volume: Int = GgwaveCodec.DEFAULT_VOLUME,
): ShortArray = encode(text.encodeToByteArray(), protocol, volume)
