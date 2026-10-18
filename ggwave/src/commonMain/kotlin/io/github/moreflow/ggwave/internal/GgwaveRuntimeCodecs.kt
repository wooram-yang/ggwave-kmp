package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveCodec
import io.github.moreflow.ggwave.GgwaveCodecConfig

/**
 * Keeps the stateful encode and decode streams isolated from each other.
 *
 * Each stream has its own lock so playback and capture can run concurrently,
 * while [close] never frees a native codec that is still in use.
 */
internal class GgwaveRuntimeCodecs private constructor(
    private val encoder: GgwaveCodec,
    private val decoder: GgwaveCodec,
) : AutoCloseable {
    private val encoderLock = RuntimeLock()
    private val decoderLock = RuntimeLock()

    fun encode(message: String, volume: Int): ShortArray = encoderLock.withLock {
        encoder.encode(message.encodeToByteArray(), volume = volume)
    }

    /** Returns the decoded message, or null when [samples] did not complete one. */
    fun decode(samples: ShortArray): String? = decoderLock.withLock {
        decoder.decode(samples)
    }?.decodeToString()

    override fun close() {
        try {
            encoderLock.withLock { encoder.close() }
        } finally {
            decoderLock.withLock { decoder.close() }
        }
    }

    companion object {
        fun create(
            config: GgwaveCodecConfig,
            factory: (GgwaveCodecConfig) -> GgwaveCodec = GgwaveCodec::create,
        ): GgwaveRuntimeCodecs {
            val encoder = factory(config)
            return try {
                GgwaveRuntimeCodecs(encoder, factory(config))
            } catch (error: Throwable) {
                encoder.close()
                throw error
            }
        }
    }
}
