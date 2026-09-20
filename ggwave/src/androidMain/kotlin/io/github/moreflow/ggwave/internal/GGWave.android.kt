package io.github.moreflow.ggwave.internal

internal actual fun createGGWave(): GGWave {
    System.loadLibrary("ggwave")
    return JVMGGWave()
}
