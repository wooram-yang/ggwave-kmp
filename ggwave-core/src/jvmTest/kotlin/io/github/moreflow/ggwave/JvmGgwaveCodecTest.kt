package io.github.moreflow.ggwave

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmGgwaveCodecTest {
    @Test
    fun encodesAndDecodesBinaryPayload() {
        val payload = byteArrayOf(1, 2, 3, 0x7F, 0x80.toByte(), 0xFF.toByte())
        val encoder = GgwaveCodec.create()
        val decoder = GgwaveCodec.create()
        try {
            val waveform = encoder.encode(payload, volume = 10)
            var decoded: ByteArray? = null
            waveform.asList().chunked(2_048).forEach { chunk ->
                decoded = decoder.decode(chunk.toShortArray()) ?: decoded
            }
            repeat(2) {
                decoded = decoder.decode(ShortArray(2_048)) ?: decoded
            }
            assertContentEquals(payload, decoded)
        } finally {
            encoder.close()
            decoder.close()
        }
    }

    @Test
    fun supportsMultipleIndependentInstances() {
        val first = GgwaveCodec.create()
        val second = GgwaveCodec.create()
        try {
            assertTrue(first.encode("first".encodeToByteArray()).isNotEmpty())
            assertTrue(second.encode("second".encodeToByteArray()).isNotEmpty())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun rejectsCallsAfterClose() {
        val codec = GgwaveCodec.create()
        codec.close()
        assertFailsWith<IllegalStateException> {
            codec.encode("closed".encodeToByteArray())
        }
    }

    @Test
    fun rejectsOversizedPayload() {
        val codec = GgwaveCodec.create()
        try {
            assertFailsWith<IllegalArgumentException> {
                codec.encode(ByteArray(GgwaveCodec.MAX_PAYLOAD_SIZE + 1))
            }
        } finally {
            codec.close()
        }
    }
}
