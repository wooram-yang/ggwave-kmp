package com.example.ggwave_multiplatform

import ggwave.ggwave_Instance
import ggwave.ggwave_SampleFormat
import ggwave.ggwave_TxProtocolId
import ggwave.ggwave_encode
import ggwave.ggwave_getDefaultParameters
import ggwave.ggwave_init
import kotlinx.cinterop.*

@OptIn(ExperimentalForeignApi::class)
object IOSGGWave: GGWave {
    override var delegate: BaseCoreManager? = null
    private var ggwaveInstance: ggwave_Instance = 0

    override fun initNative() {
        var ggwaveParams = ggwave_getDefaultParameters()
        ggwaveParams = ggwaveParams.copy {
            sampleFormatInp = ggwave_SampleFormat.GGWAVE_SAMPLE_FORMAT_I16
            sampleFormatOut = ggwave_SampleFormat.GGWAVE_SAMPLE_FORMAT_I16
            sampleRateInp = 48000f
        }

        this.ggwaveInstance = ggwave_init(ggwaveParams)
    }

    override fun processCaptureData(data: ShortArray) {}

    @OptIn(ExperimentalForeignApi::class)
    override fun sendMessage(message: String) {
        val n: Int = ggwave_encode(
            this.ggwaveInstance,
            message,
            message.length,
            ggwave_TxProtocolId.GGWAVE_TX_PROTOCOL_AUDIBLE_FAST,
            10,
            null,
            1
        )

        val waveform = ByteArray(n)
        val encodedCount = waveform.usePinned {
            ggwave_encode(
                this@IOSGGWave.ggwaveInstance,
                message,
                message.length,
                ggwave_TxProtocolId.GGWAVE_TX_PROTOCOL_AUDIBLE_FAST,
                10,
                it.addressOf(0),
                0
            )
        }

        val shortArray = ShortArray(encodedCount) {
            (waveform[it * 2] + (waveform[(it * 2) + 1].toInt() shl 8)).toShort()
        }

        this.onNativeMessageEncoded(shortArray)
    }

    override fun onNativeReceivedMessage(data: ByteArray) {
        delegate?.onNativeReceivedMessage(data)
    }

    override fun onNativeMessageEncoded(data: ShortArray) {
        delegate?.onNativeMessageEncoded(data)
    }
}

actual object GGWaveFactory {
    actual fun createInstance(): GGWave = IOSGGWave
}