package com.example.ggwave_multiplatform

object JVMGGWave: GGWave {
    override var delegate: BaseCoreManager? = null

    external override fun initNative()
    external override fun processCaptureData(shortData: ShortArray)
    override fun processCaptureData(byteData: ByteArray) {}

    external override fun sendMessage(message: String)

    override fun onNativeReceivedMessage(data: ByteArray) {
        delegate?.onNativeReceivedMessage(data)
    }

    override fun onNativeMessageEncoded(data: ShortArray) {
        delegate?.onNativeMessageEncoded(data)
    }
}