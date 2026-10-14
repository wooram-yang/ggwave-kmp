package io.github.moreflow.ggwave

enum class GgwaveProtocol(
    internal val nativeId: Int,
) {
    AudibleNormal(0),
    AudibleFast(1),
    AudibleFastest(2),
    UltrasoundNormal(3),
    UltrasoundFast(4),
    UltrasoundFastest(5),
    DualToneNormal(6),
    DualToneFast(7),
    DualToneFastest(8),
}
