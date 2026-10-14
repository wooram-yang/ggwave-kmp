package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveCodec
import io.github.moreflow.ggwave.GgwaveCodecConfig

internal actual fun createGgwaveCodec(config: GgwaveCodecConfig): GgwaveCodec {
    System.loadLibrary("ggwave")
    return JvmGgwaveCodec(config)
}
