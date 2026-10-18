package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveCodec
import io.github.moreflow.ggwave.GgwaveException
import io.github.moreflow.ggwave.GgwaveProtocol

/**
 * Handle lifecycle and argument checks shared by the platform codecs.
 *
 * Subclasses pass their native instance to [open] from their init block and implement the three native calls.
 */
internal abstract class NativeGgwaveCodec : GgwaveCodec {
    private var handle = CLOSED_HANDLE

    protected fun open(handle: Long) {
        if (handle < 0) throw GgwaveException("Failed to initialize ggwave")
        this.handle = handle
    }

    final override fun encode(payload: ByteArray, protocol: GgwaveProtocol, volume: Int): ShortArray {
        require(payload.isNotEmpty()) { "payload must not be empty" }
        require(payload.size <= GgwaveCodec.MAX_PAYLOAD_SIZE) {
            "payload must not exceed ${GgwaveCodec.MAX_PAYLOAD_SIZE} bytes"
        }
        require(volume in GgwaveCodec.MIN_VOLUME..GgwaveCodec.MAX_VOLUME) {
            "volume must be between ${GgwaveCodec.MIN_VOLUME} and ${GgwaveCodec.MAX_VOLUME}"
        }
        return nativeEncode(requireHandle(), payload, protocol, volume)
            ?: throw GgwaveException("Failed to encode payload")
    }

    final override fun decode(samples: ShortArray): ByteArray? {
        if (samples.isEmpty()) return null
        return nativeDecode(requireHandle(), samples)
    }

    final override fun close() {
        if (handle < 0) return
        nativeRelease(handle)
        handle = CLOSED_HANDLE
    }

    /** Returns the waveform, or null when ggwave fails to encode [payload]. */
    protected abstract fun nativeEncode(
        handle: Long,
        payload: ByteArray,
        protocol: GgwaveProtocol,
        volume: Int,
    ): ShortArray?

    /** Returns the decoded payload, or null when [samples] did not complete one. */
    protected abstract fun nativeDecode(handle: Long, samples: ShortArray): ByteArray?

    protected abstract fun nativeRelease(handle: Long)

    private fun requireHandle(): Long {
        check(handle >= 0) { "GgwaveCodec is closed" }
        return handle
    }

    private companion object {
        const val CLOSED_HANDLE = -1L
    }
}
