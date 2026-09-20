package io.github.wooramyang.ggwave.internal

internal actual fun createGGWave(): GGWave {
    NativeLoader.load()
    return JVMGGWave()
}
