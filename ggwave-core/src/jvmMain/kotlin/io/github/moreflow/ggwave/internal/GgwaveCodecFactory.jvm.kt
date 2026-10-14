package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveCodec
import io.github.moreflow.ggwave.GgwaveCodecConfig

internal actual fun createGgwaveCodec(config: GgwaveCodecConfig): GgwaveCodec {
    NativeLoader.load()
    return JvmGgwaveCodec(config)
}
