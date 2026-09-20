package io.github.wooramyang.ggwave.internal

internal actual fun createGGWave(): GGWave {
    System.loadLibrary("ggwave")
    return JVMGGWave()
}
