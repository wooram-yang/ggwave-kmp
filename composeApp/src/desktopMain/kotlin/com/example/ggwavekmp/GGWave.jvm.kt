package com.example.ggwavekmp

actual object GGWaveFactory {
    init {
        val currentOS = System.getProperty("os.name")
        if(currentOS == "Mac OS X") {
            System.loadLibrary("ggwave")
        } else {
            System.loadLibrary("libggwave")
        }
    }

    actual fun createInstance(): GGWave = JVMGGWave
}
