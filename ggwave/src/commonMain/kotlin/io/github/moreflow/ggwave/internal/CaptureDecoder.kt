package io.github.moreflow.ggwave.internal

import kotlin.math.abs

/** Decodes one capture stream's sample buffers and reports the input level. */
internal class CaptureDecoder(
    private val codecs: GgwaveRuntimeCodecs,
    private val listener: GgwaveRuntime.Listener,
) {
    private var bufferCount = 0

    fun decode(samples: ShortArray) {
        if (++bufferCount % LEVEL_UPDATE_INTERVAL_BUFFERS == 0) {
            listener.onCaptureLevelChanged(samples.peakLevel())
        }
        codecs.decode(samples)?.let(listener::onReceivedMessage)
    }

    private fun ShortArray.peakLevel(): Float {
        val peak = maxOfOrNull { abs(it.toInt()) } ?: 0
        return minOf(peak.toFloat() / Short.MAX_VALUE, 1f)
    }

    private companion object {
        const val LEVEL_UPDATE_INTERVAL_BUFFERS = 6
    }
}
