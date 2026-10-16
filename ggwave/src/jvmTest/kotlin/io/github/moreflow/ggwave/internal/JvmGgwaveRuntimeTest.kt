package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveConfig
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import javax.sound.sampled.LineListener
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmGgwaveRuntimeTest {
    @Test
    fun keepsClipOpenUntilPlaybackStops() {
        val fakeClip = FakeClip()
        val playbackEnded = CountDownLatch(1)
        val playbackFailed = AtomicBoolean(false)
        val runtime = JvmGgwaveRuntime(GgwaveConfig(), clipFactory = { fakeClip.clip })
        runtime.setListeners(
            onReceivedMessage = {},
            onCaptureLevelChanged = {},
            onCaptureFailed = {},
            onPlayEnded = { playbackEnded.countDown() },
            onPlayFailed = {
                playbackFailed.set(true)
                playbackEnded.countDown()
            },
        )

        try {
            runtime.startPlayback("hello")

            assertTrue(fakeClip.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(fakeClip.closed.get())

            fakeClip.finishPlayback()

            assertTrue(playbackEnded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(fakeClip.closedLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(playbackFailed.get())
        } finally {
            runtime.close()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}

private class FakeClip {
    val started = CountDownLatch(1)
    val closedLatch = CountDownLatch(1)
    val closed = AtomicBoolean(false)
    private var listener: LineListener? = null

    val clip: Clip = Proxy.newProxyInstance(
        Clip::class.java.classLoader,
        arrayOf(Clip::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            "addLineListener" -> listener = arguments?.get(0) as LineListener
            "removeLineListener" -> {
                if (listener === arguments?.get(0)) listener = null
            }
            "start" -> started.countDown()
            "close" -> {
                closed.set(true)
                closedLatch.countDown()
            }
            "equals" -> return@newProxyInstance proxy === arguments?.get(0)
            "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
            "toString" -> return@newProxyInstance "FakeClip"
        }
        defaultValue(method.returnType)
    } as Clip

    fun finishPlayback() {
        listener?.update(LineEvent(clip, LineEvent.Type.STOP, 0L))
    }
}

private fun defaultValue(type: Class<*>): Any? = when (type) {
    java.lang.Boolean.TYPE -> false
    java.lang.Byte.TYPE -> 0.toByte()
    java.lang.Short.TYPE -> 0.toShort()
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    java.lang.Float.TYPE -> 0f
    java.lang.Double.TYPE -> 0.0
    java.lang.Character.TYPE -> 0.toChar()
    else -> null
}
