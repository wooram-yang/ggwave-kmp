package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveCodec
import io.github.moreflow.ggwave.GgwaveCodecConfig
import io.github.moreflow.ggwave.GgwaveProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GgwaveRuntimeCodecsTest {
    @Test
    fun routesEncodeAndDecodeToSeparateCodecsAndClosesBoth() {
        val createdCodecs = mutableListOf<FakeCodec>()
        val codecs = GgwaveRuntimeCodecs.create(GgwaveCodecConfig()) {
            FakeCodec(decoded = "hello".encodeToByteArray()).also(createdCodecs::add)
        }
        val (encoder, decoder) = createdCodecs

        codecs.encode("hi", GgwaveProtocol.UltrasoundFast, volume = 30)
        val decoded = codecs.decode(ShortArray(4))

        assertEquals(listOf("hi"), encoder.encodedPayloads)
        assertEquals(GgwaveProtocol.UltrasoundFast, encoder.lastProtocol)
        assertEquals(30, encoder.lastVolume)
        assertEquals(0, encoder.decodeCount)
        assertEquals(1, decoder.decodeCount)
        assertTrue(decoder.encodedPayloads.isEmpty())
        assertEquals("hello", decoded)

        codecs.close()

        assertTrue(createdCodecs.all(FakeCodec::closed))
    }

    @Test
    fun decodeReturnsNullWhenNoMessageIsComplete() {
        val codecs = GgwaveRuntimeCodecs.create(GgwaveCodecConfig()) { FakeCodec(decoded = null) }

        assertNull(codecs.decode(ShortArray(4)))
        codecs.close()
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

internal class FakeCodec(
    private val decoded: ByteArray? = null,
) : GgwaveCodec {
    var closed = false
    var decodeCount = 0
    var lastVolume: Int? = null
    var lastProtocol: GgwaveProtocol? = null
    val encodedPayloads = mutableListOf<String>()

    override fun encode(payload: ByteArray, protocol: GgwaveProtocol, volume: Int): ShortArray {
        encodedPayloads += payload.decodeToString()
        lastProtocol = protocol
        lastVolume = volume
        return ShortArray(1)
    }

    override fun decode(samples: ShortArray): ByteArray? {
        decodeCount += 1
        return decoded
    }

    override fun close() {
        closed = true
    }
}
