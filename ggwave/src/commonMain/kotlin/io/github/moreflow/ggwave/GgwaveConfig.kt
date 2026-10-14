package io.github.moreflow.ggwave

data class GgwaveConfig(
    val sampleRate: Int = 48_000,
    val captureBufferSize: Int = 4 * 1024,
    val volume: Int = 50,
) {
    init {
        require(sampleRate in GgwaveCodecConfig.MIN_SAMPLE_RATE..GgwaveCodecConfig.MAX_SAMPLE_RATE) {
            "sampleRate must be between ${GgwaveCodecConfig.MIN_SAMPLE_RATE} and ${GgwaveCodecConfig.MAX_SAMPLE_RATE}"
        }
        require(captureBufferSize > 0 && captureBufferSize % 2 == 0) {
            "captureBufferSize must be a positive, even byte count"
        }
        require(volume in 0..100) { "volume must be between 0 and 100" }
    }
}
