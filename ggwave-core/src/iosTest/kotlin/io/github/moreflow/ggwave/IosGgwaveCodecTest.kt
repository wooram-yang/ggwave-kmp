package io.github.moreflow.ggwave

import kotlin.test.Test
import kotlin.test.assertContentEquals

class IosGgwaveCodecTest {
    @Test
    fun decodesEncodedWaveformInCaptureSizedChunks() {
        val payload = "hello from iOS".encodeToByteArray()
        val encoder = GgwaveCodec.create()
        val decoder = GgwaveCodec.create()
        try {
            val waveform = encoder.encode(payload)
            var decoded: ByteArray? = null
            waveform.asList().chunked(CAPTURE_SAMPLES_PER_BUFFER).forEach { chunk ->
                decoded = decoder.decode(chunk.toShortArray()) ?: decoded
            }
            repeat(2) {
                decoded = decoder.decode(ShortArray(CAPTURE_SAMPLES_PER_BUFFER)) ?: decoded
            }

            assertContentEquals(payload, decoded)
        } finally {
            encoder.close()
            decoder.close()
        }
    }

    @Test
    fun decodesConsecutiveWaveforms() {
        val encoder = GgwaveCodec.create()
        val decoder = GgwaveCodec.create()
        val decoded = mutableListOf<String>()
        try {
            listOf("first", "second", "third").forEach { message ->
                val waveform = encoder.encode(message.encodeToByteArray())
                waveform.asList().chunked(CAPTURE_SAMPLES_PER_BUFFER).forEach { chunk ->
                    decoder.decode(chunk.toShortArray())?.let { decoded += it.decodeToString() }
                }
                repeat(4) {
                    decoder.decode(ShortArray(CAPTURE_SAMPLES_PER_BUFFER))
                        ?.let { decoded += it.decodeToString() }
                }
            }

            assertContentEquals(listOf("first", "second", "third"), decoded)
        } finally {
            encoder.close()
            decoder.close()
        }
    }

    private companion object {
        const val CAPTURE_SAMPLES_PER_BUFFER = 2_048
    }
}
