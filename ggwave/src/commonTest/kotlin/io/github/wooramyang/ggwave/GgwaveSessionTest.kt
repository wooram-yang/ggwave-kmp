package io.github.wooramyang.ggwave

import io.github.wooramyang.ggwave.internal.GgwaveRuntime
import io.github.wooramyang.ggwave.internal.SessionGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

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
        val runtime = FakeGgwaveRuntime()
        val session = GgwaveSession(runtime)

        session.send("hello")
        assertEquals("hello", runtime.lastMessage)
        assertFalse(session.isPlaying.value)
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
        assertFalse(SessionGuard.tryAcquire())
        SessionGuard.release()
        assertTrue(SessionGuard.tryAcquire())
        SessionGuard.release()
    }
}

private class FakeGgwaveRuntime(
    private val failPlayback: Boolean = false,
) : GgwaveRuntime {
    var capturing = false
    var lastMessage: String? = null
    var closed = false
    private var onPlayEnded: () -> Unit = {}
    private var onPlayFailed: (Throwable) -> Unit = {}

    override fun startCapturing() {
        capturing = true
    }

    override fun stopCapturing() {
        capturing = false
    }

    override fun startPlayback(message: String) {
        lastMessage = message
        if (failPlayback) {
            onPlayFailed(GgwaveException("encode failed"))
        } else {
            onPlayEnded()
        }
    }

    override fun stopPlayback() {}

    override fun setListeners(
        onReceivedMessage: (String) -> Unit,
        onPlayEnded: () -> Unit,
        onPlayFailed: (Throwable) -> Unit,
    ) {
        this.onPlayEnded = onPlayEnded
        this.onPlayFailed = onPlayFailed
    }

    override fun close() {
        closed = true
    }
}
