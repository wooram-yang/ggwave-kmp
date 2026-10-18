package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveCodecConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureDecoderTest {
    @Test
    fun reportsPeakLevelEverySixthBuffer() {
        val levels = mutableListOf<Float>()
        val codecs = GgwaveRuntimeCodecs.create(GgwaveCodecConfig()) { FakeCodec() }
        val decoder = CaptureDecoder(
            codecs,
            object : GgwaveRuntime.Listener {
                override fun onCaptureLevelChanged(level: Float) {
                    levels += level
                }
            },
        )

        repeat(5) { decoder.decode(shortArrayOf(Short.MAX_VALUE)) }
        assertEquals(emptyList(), levels)

        decoder.decode(shortArrayOf(0, (Short.MAX_VALUE / 2).toShort(), Short.MIN_VALUE))
        assertEquals(listOf(1f), levels)

        repeat(5) { decoder.decode(shortArrayOf(0)) }
        decoder.decode(shortArrayOf(0))
        assertEquals(listOf(1f, 0f), levels)

        codecs.close()
    }

    @Test
    fun forwardsDecodedMessages() {
        val messages = mutableListOf<String>()
        val codecs = GgwaveRuntimeCodecs.create(GgwaveCodecConfig()) {
            FakeCodec(decoded = "hello".encodeToByteArray())
        }
        val decoder = CaptureDecoder(
            codecs,
            object : GgwaveRuntime.Listener {
                override fun onReceivedMessage(message: String) {
                    messages += message
                }
            },
        )

        decoder.decode(ShortArray(4))

        assertEquals(listOf("hello"), messages)
        codecs.close()
    }
}
