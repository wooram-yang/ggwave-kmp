package com.example.ggwavekmp

actual object GGWaveFactory {
    init {
        System.loadLibrary("ggwave")
    }

    actual fun createInstance(): GGWave = JVMGGWave
}