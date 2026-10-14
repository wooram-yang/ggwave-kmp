package io.github.moreflow.ggwave

data class GgwaveCodecConfig(
    val inputSampleRate: Int = DEFAULT_SAMPLE_RATE,
    val outputSampleRate: Int = DEFAULT_SAMPLE_RATE,
) {
    init {
        require(inputSampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) {
            "inputSampleRate must be between $MIN_SAMPLE_RATE and $MAX_SAMPLE_RATE"
        }
        require(outputSampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) {
            "outputSampleRate must be between $MIN_SAMPLE_RATE and $MAX_SAMPLE_RATE"
        }
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val MIN_SAMPLE_RATE = 8_000
        const val MAX_SAMPLE_RATE = 96_000
    }
}
