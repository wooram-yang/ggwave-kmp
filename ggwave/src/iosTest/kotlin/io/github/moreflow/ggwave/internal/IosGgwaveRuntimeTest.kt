package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveCodec
import io.github.moreflow.ggwave.GgwaveConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class IosGgwaveRuntimeTest {
    @Test
    fun capturedPcmBytesReachReceivedMessageListener() {
        val encoder = GgwaveCodec.create()
        val runtime = IosGgwaveRuntime(GgwaveConfig())
        val message = "hello from another device"
        var received: String? = null

        try {
            runtime.setListeners(
                onReceivedMessage = { received = it },
                onCaptureLevelChanged = {},
                onCaptureFailed = { throw it },
                onPlayEnded = {},
                onPlayFailed = { throw it },
            )
            val waveform = encoder.encode(message.encodeToByteArray())
            waveform.asList().chunked(2_048).forEach { chunk ->
                runtime.onCaptureBytes(chunk.toShortArray().toLittleEndianByteArray())
            }
            repeat(2) {
                runtime.onCaptureBytes(ShortArray(2_048).toLittleEndianByteArray())
            }

            assertEquals(message, received)
        } finally {
            runtime.close()
            encoder.close()
        }
    }

    @Test
    fun arbitraryAudioQueueChunksAreAggregatedBeforeDecoding() {
        val encoder = GgwaveCodec.create()
        val runtime = IosGgwaveRuntime(GgwaveConfig())
        val message = "variable callback chunks"
        var received: String? = null

        try {
            runtime.setListeners(
                onReceivedMessage = { received = it },
                onCaptureLevelChanged = {},
                onCaptureFailed = { throw it },
                onPlayEnded = {},
                onPlayFailed = { throw it },
            )
            val capturedBytes =
                encoder.encode(message.encodeToByteArray()).toLittleEndianByteArray() +
                    ByteArray(8_192)
            capturedBytes.asList().chunked(188).forEach { chunk ->
                runtime.onCaptureBytes(chunk.toByteArray())
            }

            assertEquals(message, received)
        } finally {
            runtime.close()
            encoder.close()
        }
    }
}
