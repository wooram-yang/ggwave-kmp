package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveCodec
import io.github.moreflow.ggwave.GgwaveCodecConfig
import io.github.moreflow.ggwave.GgwaveException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.Job

class AudioQueueInputTest {
    @Test
    fun capturedPcmBytesReachReceivedMessageListener() {
        val message = "hello from another device"
        val received = decodeCapturedBytes { input, encoder ->
            val waveform = encoder.encode(message.encodeToByteArray())
            waveform.asList().chunked(2_048).forEach { chunk ->
                input.onBytes(chunk.toShortArray().toLittleEndianByteArray())
            }
            repeat(2) {
                input.onBytes(ShortArray(2_048).toLittleEndianByteArray())
            }
        }

        assertEquals(message, received)
    }

    @Test
    fun arbitraryAudioQueueChunksAreAggregatedBeforeDecoding() {
        val message = "variable callback chunks"
        val received = decodeCapturedBytes { input, encoder ->
            val capturedBytes =
                encoder.encode(message.encodeToByteArray()).toLittleEndianByteArray() +
                    ByteArray(8_192)
            capturedBytes.asList().chunked(188).forEach { chunk ->
                input.onBytes(chunk.toByteArray())
            }
        }

        assertEquals(message, received)
    }

    @Test
    fun failReportsOnceAndCancelsTheStream() {
        val job = Job()
        val failures = mutableListOf<Throwable>()
        val codecs = GgwaveRuntimeCodecs.create(GgwaveCodecConfig())
        val input = AudioQueueInput(
            chunkByteSize = 4_096,
            decoder = CaptureDecoder(codecs, object : GgwaveRuntime.Listener {}),
            job = job,
            onFailure = { failures += it },
        )

        try {
            input.fail(GgwaveException("first"))
            input.fail(GgwaveException("second"))

            assertFalse(input.isActive)
            assertEquals(listOf("first"), failures.map { it.message })
        } finally {
            codecs.close()
        }
    }

    private fun decodeCapturedBytes(feed: (AudioQueueInput, GgwaveCodec) -> Unit): String? {
        val encoder = GgwaveCodec.create()
        val codecs = GgwaveRuntimeCodecs.create(GgwaveCodecConfig())
        var received: String? = null
        val input = AudioQueueInput(
            chunkByteSize = 4_096,
            decoder = CaptureDecoder(
                codecs,
                object : GgwaveRuntime.Listener {
                    override fun onReceivedMessage(message: String) {
                        received = message
                    }
                },
            ),
            job = Job(),
            onFailure = { throw it },
        )

        try {
            feed(input, encoder)
        } finally {
            codecs.close()
            encoder.close()
        }
        return received
    }
}
