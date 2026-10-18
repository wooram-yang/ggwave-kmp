package io.github.moreflow.ggwave

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GgwaveCodecProtocolTest {
    @Test
    fun everyProtocolRoundTrips() {
        GgwaveProtocol.entries.forEach { protocol ->
            assertEquals("hello $protocol", roundTrip("hello $protocol", protocol), "protocol $protocol")
        }
    }

    @Test
    fun protocolChoiceChangesTheWaveform() {
        GgwaveCodec.create().use { encoder ->
            val payload = "same payload".encodeToByteArray()
            val normal = encoder.encode(payload, GgwaveProtocol.AudibleNormal)
            val fastest = encoder.encode(payload, GgwaveProtocol.AudibleFastest)

            assertTrue(fastest.size < normal.size, "fastest ${fastest.size} vs normal ${normal.size}")
        }
    }

    private fun roundTrip(message: String, protocol: GgwaveProtocol): String? {
        val encoder = GgwaveCodec.create()
        val decoder = GgwaveCodec.create()
        try {
            val waveform = encoder.encode(message.encodeToByteArray(), protocol)
            var decoded: ByteArray? = null
            waveform.asList().chunked(SAMPLES_PER_BUFFER).forEach { chunk ->
                decoded = decoder.decode(chunk.toShortArray()) ?: decoded
            }
            repeat(4) {
                decoded = decoder.decode(ShortArray(SAMPLES_PER_BUFFER)) ?: decoded
            }
            return decoded?.decodeToString()
        } finally {
            encoder.close()
            decoder.close()
        }
    }

    private companion object {
        const val SAMPLES_PER_BUFFER = 2_048
    }
}
