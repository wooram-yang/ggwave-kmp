package io.github.moreflow.ggwave

data class GgwaveConfig(
    val sampleRate: Int = GgwaveCodecConfig.DEFAULT_SAMPLE_RATE,
    val captureBufferSize: Int = 4 * 1024,
    val volume: Int = GgwaveCodec.DEFAULT_VOLUME,
    /** Default protocol for [GgwaveSession.send]. Receiving detects every protocol automatically. */
    val protocol: GgwaveProtocol = GgwaveCodec.DEFAULT_PROTOCOL,
) {
    init {
        require(sampleRate in GgwaveCodecConfig.MIN_SAMPLE_RATE..GgwaveCodecConfig.MAX_SAMPLE_RATE) {
            "sampleRate must be between ${GgwaveCodecConfig.MIN_SAMPLE_RATE} and ${GgwaveCodecConfig.MAX_SAMPLE_RATE}"
        }
        require(captureBufferSize > 0 && captureBufferSize % 2 == 0) {
            "captureBufferSize must be a positive, even byte count"
        }
        require(volume in GgwaveCodec.MIN_VOLUME..GgwaveCodec.MAX_VOLUME) {
            "volume must be between ${GgwaveCodec.MIN_VOLUME} and ${GgwaveCodec.MAX_VOLUME}"
        }
    }
}
