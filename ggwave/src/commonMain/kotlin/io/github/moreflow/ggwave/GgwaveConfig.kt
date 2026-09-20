package io.github.moreflow.ggwave

data class GgwaveConfig(
    val sampleRate: Int = 48_000,
    val captureBufferSize: Int = 4 * 1024,
    val volume: Int = 50,
)
