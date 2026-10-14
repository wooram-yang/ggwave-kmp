package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveCodec
import io.github.moreflow.ggwave.GgwaveCodecConfig
import io.github.moreflow.ggwave.GgwaveProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class GgwaveRuntimeCodecsTest {
    @Test
    fun createsIndependentEncoderAndDecoderAndClosesBoth() {
        val createdCodecs = mutableListOf<FakeCodec>()
        val codecs = GgwaveRuntimeCodecs.create(GgwaveCodecConfig()) {
            FakeCodec().also(createdCodecs::add)
        }

        assertEquals(2, createdCodecs.size)
        assertNotSame(codecs.encoder, codecs.decoder)

        codecs.close()

        assertTrue(createdCodecs.all(FakeCodec::closed))
    }

    @Test
    fun closesEncoderWhenDecoderCreationFails() {
        val encoder = FakeCodec()
        var invocationCount = 0

        assertFailsWith<IllegalStateException> {
            GgwaveRuntimeCodecs.create(GgwaveCodecConfig()) {
                invocationCount += 1
                if (invocationCount == 1) encoder else error("decoder creation failed")
            }
        }

        assertTrue(encoder.closed)
    }
}

private class FakeCodec : GgwaveCodec {
    var closed = false

    override fun encode(payload: ByteArray, protocol: GgwaveProtocol, volume: Int): ShortArray =
        ShortArray(0)

    override fun decode(samples: ShortArray): ByteArray? = null

    override fun close() {
        closed = true
    }
}
