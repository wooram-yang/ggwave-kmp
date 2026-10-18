package io.github.moreflow.ggwave

import io.github.moreflow.ggwave.internal.GgwaveRuntime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GgwaveSessionConcurrencyTest {
    @Test
    fun concurrentStartAndStopKeepSessionAndRuntimeInSync() {
        val runtime = CountingRuntime()
        val session = GgwaveSession(runtime)
        val threads = 8
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)

        try {
            repeat(threads) { worker ->
                executor.execute {
                    ready.countDown()
                    go.await()
                    repeat(ITERATIONS) { i ->
                        if ((worker + i) % 2 == 0) session.startCapture() else session.stopCapture()
                    }
                }
            }
            ready.await()
            go.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))

            assertEquals(session.isCapturing.value, runtime.activeCaptures.get() == 1)
            assertTrue(runtime.activeCaptures.get() in 0..1)
            assertEquals(0, runtime.overlappingStarts.get())
        } finally {
            session.close()
        }
    }

    private class CountingRuntime : GgwaveRuntime {
        val activeCaptures = AtomicInteger(0)
        val overlappingStarts = AtomicInteger(0)

        override fun startCapturing() {
            if (activeCaptures.incrementAndGet() != 1) overlappingStarts.incrementAndGet()
        }

        override fun stopCapturing() {
            activeCaptures.decrementAndGet()
        }

        override fun startPlayback(message: String) {}
        override fun stopPlayback() {}
        override fun setListener(listener: GgwaveRuntime.Listener) {}
        override fun close() {}
    }

    private companion object {
        const val ITERATIONS = 10_000
    }
}
