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
        protocol: GgwaveProtocol = GgwaveProtocol.AudibleFast,
        volume: Int = DEFAULT_VOLUME,
    ): ShortArray

    fun decode(samples: ShortArray): ByteArray?

    override fun close()

    companion object {
        const val DEFAULT_VOLUME = 50
        const val MAX_PAYLOAD_SIZE = 140

        fun create(config: GgwaveCodecConfig = GgwaveCodecConfig()): GgwaveCodec =
            createGgwaveCodec(config)
    }
}

fun GgwaveCodec.encodeText(
    text: String,
    protocol: GgwaveProtocol = GgwaveProtocol.AudibleFast,
    volume: Int = GgwaveCodec.DEFAULT_VOLUME,
): ShortArray = encode(text.encodeToByteArray(), protocol, volume)
