package io.github.moreflow.ggwave

import io.github.moreflow.ggwave.internal.GgwaveRuntime
import io.github.moreflow.ggwave.internal.SessionGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class GgwaveSessionTest {
    @Test
    fun startAndStopCaptureUpdatesState() = runTest {
        val runtime = FakeGgwaveRuntime()
        val session = GgwaveSession(runtime)

        assertFalse(session.isCapturing.value)
        session.startCapture()
        assertTrue(session.isCapturing.value)
        assertTrue(runtime.capturing)

        session.stopCapture()
        assertFalse(session.isCapturing.value)
        assertFalse(runtime.capturing)
    }

    @Test
    fun sendBlankMessageIsRejected() = runTest {
        val runtime = FakeGgwaveRuntime()
        val session = GgwaveSession(runtime)

        assertFailsWith<IllegalArgumentException> {
            session.send("   ")
        }
        assertNull(runtime.lastMessage)
        assertFalse(session.isPlaying.value)
    }

    @Test
    fun sendWaitsUntilPlaybackEnds() = runTest {
        val runtime = FakeGgwaveRuntime(completePlaybackImmediately = false)
        val session = GgwaveSession(runtime)

        val sendJob = launch { session.send("hello") }
        runCurrent()

        assertEquals("hello", runtime.lastMessage)
        assertTrue(session.isPlaying.value)
        assertTrue(sendJob.isActive)

        runtime.finishPlayback()
        sendJob.join()
        assertFalse(session.isPlaying.value)
    }

    @Test
    fun sendPreservesLeadingAndTrailingWhitespace() = runTest {
        val runtime = FakeGgwaveRuntime()
        val session = GgwaveSession(runtime)

        session.send(" hello ")

        assertEquals(" hello ", runtime.lastMessage)
    }

    @Test
    fun sendFailsWhenPlaybackFails() = runTest {
        val runtime = FakeGgwaveRuntime(failPlayback = true)
        val session = GgwaveSession(runtime)

        assertFailsWith<GgwaveException> {
            session.send("hello")
        }
        assertFalse(session.isPlaying.value)
    }

    @Test
    fun closeStopsCapture() = runTest {
        val runtime = FakeGgwaveRuntime()
        val session = GgwaveSession(runtime)
        session.startCapture()
        session.close()
        assertFalse(session.isCapturing.value)
        assertTrue(runtime.closed)
    }

    @Test
    fun captureFailureResetsCaptureState() = runTest {
        val runtime = FakeGgwaveRuntime()
        val session = GgwaveSession(runtime)
        session.startCapture()

        runtime.failCapture()

        assertFalse(session.isCapturing.value)
    }

    @Test
    fun synchronousCaptureFailureRollsBackCaptureState() = runTest {
        val session = GgwaveSession(FakeGgwaveRuntime(failCaptureOnStart = true))

        assertFailsWith<GgwaveException> {
            session.startCapture()
        }

        assertFalse(session.isCapturing.value)
    }

    @Test
    fun closeCancelsPendingPlaybackAndStopsRuntime() = runTest {
        val runtime = FakeGgwaveRuntime(completePlaybackImmediately = false)
        val session = GgwaveSession(runtime)
        val sendJob = launch { session.send("hello") }
        runCurrent()

        session.close()
        sendJob.join()

        assertTrue(sendJob.isCancelled)
        assertTrue(runtime.playbackStopped)
        assertFalse(session.isPlaying.value)
    }

    @Test
    fun startCaptureAfterCloseThrows() = runTest {
        val session = GgwaveSession(FakeGgwaveRuntime())
        session.close()
        assertFailsWith<IllegalStateException> {
            session.startCapture()
        }
    }

    @Test
    fun sessionGuardAllowsOnlyOneAcquire() {
        assertTrue(SessionGuard.tryAcquire())
        try {
            assertFalse(SessionGuard.tryAcquire())
        } finally {
            SessionGuard.release()
        }
        assertTrue(SessionGuard.tryAcquire())
        SessionGuard.release()
    }
}

private class FakeGgwaveRuntime(
    private val failPlayback: Boolean = false,
    private val completePlaybackImmediately: Boolean = true,
    private val failCaptureOnStart: Boolean = false,
) : GgwaveRuntime {
    var capturing = false
    var lastMessage: String? = null
    var closed = false
    var playbackStopped = false
    private var onCaptureFailed: (Throwable) -> Unit = {}
    private var onPlayEnded: () -> Unit = {}
    private var onPlayFailed: (Throwable) -> Unit = {}

    override fun startCapturing() {
        if (failCaptureOnStart) {
            throw GgwaveException("capture failed")
        }
        capturing = true
    }

    override fun stopCapturing() {
        capturing = false
    }

    override fun startPlayback(message: String) {
        lastMessage = message
        if (failPlayback) {
            onPlayFailed(GgwaveException("encode failed"))
        } else if (completePlaybackImmediately) {
            onPlayEnded()
        }
    }

    override fun stopPlayback() {
        playbackStopped = true
    }

    override fun setListeners(
        onReceivedMessage: (String) -> Unit,
        onCaptureFailed: (Throwable) -> Unit,
        onPlayEnded: () -> Unit,
        onPlayFailed: (Throwable) -> Unit,
    ) {
        this.onCaptureFailed = onCaptureFailed
        this.onPlayEnded = onPlayEnded
        this.onPlayFailed = onPlayFailed
    }

    override fun close() {
        closed = true
    }

    fun finishPlayback() {
        onPlayEnded()
    }

    fun failCapture() {
        capturing = false
        onCaptureFailed(GgwaveException("capture failed"))
    }
}
