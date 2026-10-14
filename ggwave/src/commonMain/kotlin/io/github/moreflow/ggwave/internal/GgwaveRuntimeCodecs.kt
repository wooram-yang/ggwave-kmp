package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveCodec
import io.github.moreflow.ggwave.GgwaveCodecConfig

/** Keeps the stateful encode and decode streams isolated from each other. */
internal class GgwaveRuntimeCodecs private constructor(
    val encoder: GgwaveCodec,
    val decoder: GgwaveCodec,
) : AutoCloseable {
    override fun close() {
        try {
            encoder.close()
        } finally {
            decoder.close()
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
