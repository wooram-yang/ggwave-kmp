package io.github.moreflow.ggwave.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveStreamTest {
    @Test
    fun releasesResourceWhenStreamCompletes() = runTest {
        val released = mutableListOf<String>()
        val stream = ActiveStream<String> { released += it }

        stream.start(backgroundScope, resource = "line", onFailure = { throw it }) {}
        runCurrent()

        assertEquals(listOf("line"), released)
    }

    @Test
    fun stopReleasesResourceOnceAndCancelsBlock() = runTest {
        val released = mutableListOf<String>()
        val stream = ActiveStream<String> { released += it }
        var cancelled = false

        stream.start(backgroundScope, resource = "line", onFailure = { throw it }) {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        runCurrent()
        stream.stop()
        runCurrent()

        assertTrue(cancelled)
        assertEquals(listOf("line"), released)
    }

    @Test
    fun attachedResourceIsReleasedOnCompletion() = runTest {
        val released = mutableListOf<String>()
        val stream = ActiveStream<String> { released += it }

        stream.start(backgroundScope, onFailure = { throw it }) {
            stream.attach("track")
        }
        runCurrent()

        assertEquals(listOf("track"), released)
    }

    @Test
    fun attachAfterStopReleasesResourceImmediately() = runTest {
        val released = mutableListOf<String>()
        val stream = ActiveStream<String> { released += it }
        val gate = CompletableDeferred<Unit>()
        var attachedAfterStop = false

        stream.start(backgroundScope, onFailure = { throw it }) {
            try {
                gate.await()
            } finally {
                // Simulates a resource that finished opening right as the stream was stopped.
                withContext(NonCancellable) {
                    runCatching { stream.attach("track") }
                }
                attachedAfterStop = true
            }
        }
        runCurrent()
        stream.stop()
        runCurrent()

        assertTrue(attachedAfterStop)
        assertEquals(listOf("track"), released)
    }

    @Test
    fun reportsFailuresOnlyWhileActive() = runTest {
        val failures = mutableListOf<Throwable>()
        val stream = ActiveStream<String>()

        stream.start(backgroundScope, onFailure = { failures += it }) {
            error("boom")
        }
        runCurrent()

        assertEquals(1, failures.size)
        assertEquals("boom", failures.single().message)
    }

    @Test
    fun newStreamIsNotAffectedByPreviousStreamFinishing() = runTest {
        val released = mutableListOf<String>()
        val stream = ActiveStream<String> { released += it }

        stream.start(backgroundScope, resource = "first", onFailure = { throw it }) {
            awaitCancellation()
        }
        runCurrent()
        stream.stop()
        stream.start(backgroundScope, resource = "second", onFailure = { throw it }) {
            awaitCancellation()
        }
        runCurrent()

        assertEquals(listOf("first"), released)

        stream.stop()
        assertEquals(listOf("first", "second"), released)
    }
}
