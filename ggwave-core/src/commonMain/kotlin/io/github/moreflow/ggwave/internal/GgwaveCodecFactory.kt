package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveCodec
import io.github.moreflow.ggwave.GgwaveCodecConfig

internal expect fun createGgwaveCodec(config: GgwaveCodecConfig): GgwaveCodec
