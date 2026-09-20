package io.github.moreflow.ggwave.internal

internal actual fun createGGWave(): GGWave {
    NativeLoader.load()
    return JVMGGWave()
}
