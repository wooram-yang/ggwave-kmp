package io.github.moreflow.ggwave.internal

import kotlinx.coroutines.Job

/**
 * Receives one capture stream's AudioQueue buffers on the AudioQueue thread.
 *
 * Buffers arrive in arbitrary sizes, so they are collected into fixed-size chunks before decoding.
 */
internal class AudioQueueInput(
    chunkByteSize: Int,
    private val decoder: CaptureDecoder,
    private val job: Job,
    private val onFailure: (Throwable) -> Unit,
) {
    private val chunk = ByteArray(chunkByteSize)
    private var chunkSize = 0

    val isActive: Boolean
        get() = job.isActive

    fun onBytes(bytes: ByteArray) {
        var sourceOffset = 0
        while (sourceOffset < bytes.size) {
            val byteCount = minOf(chunk.size - chunkSize, bytes.size - sourceOffset)
            bytes.copyInto(
                destination = chunk,
                destinationOffset = chunkSize,
                startIndex = sourceOffset,
                endIndex = sourceOffset + byteCount,
            )
            chunkSize += byteCount
            sourceOffset += byteCount
            if (chunkSize == chunk.size) {
                decoder.decode(chunk.toLittleEndianShortArray())
                chunkSize = 0
            }
        }
    }

    /** Reports [error] and stops the stream; the queue is disposed when its coroutine finishes. */
    fun fail(error: Throwable) {
        if (!job.isActive) return
        onFailure(error)
        job.cancel()
    }
}
