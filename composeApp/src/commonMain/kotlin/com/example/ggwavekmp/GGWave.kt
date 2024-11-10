package com.example.ggwavekmp

interface GGWave {
    var delegate: BaseCoreManager?

    fun initNative()
    fun processCaptureData(shortData: ShortArray)
    fun processCaptureData(byteData: ByteArray)
    fun sendMessage(message: String)

    fun onNativeReceivedMessage(data: ByteArray)
    fun onNativeMessageEncoded(data: ShortArray)
}

expect object GGWaveFactory {
    fun createInstance(): GGWave
}