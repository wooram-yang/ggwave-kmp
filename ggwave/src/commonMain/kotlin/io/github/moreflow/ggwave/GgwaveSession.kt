package io.github.moreflow.ggwave

import io.github.moreflow.ggwave.internal.GgwaveRuntime
import io.github.moreflow.ggwave.internal.RuntimeLock
import io.github.moreflow.ggwave.internal.SessionGuard
import io.github.moreflow.ggwave.internal.createGgwaveRuntime
import io.github.moreflow.ggwave.internal.withLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalAtomicApi::class)
class GgwaveSession internal constructor(
    private val runtime: GgwaveRuntime,
    private val ownsProcessSlot: Boolean = false,
) {
    private val sendMutex = Mutex()
    private val _receivedMessages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val _captureErrors = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)
    private val _captureLevel = MutableStateFlow(0f)
    private val _isCapturing = MutableStateFlow(false)
    private val _isPlaying = MutableStateFlow(false)

    // Runtime listener callbacks arrive on background threads, while the API is called from the app's threads.
    private val captureLock = RuntimeLock()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var playEnded: CompletableDeferred<Unit>? = null

    val receivedMessages: SharedFlow<String> = _receivedMessages.asSharedFlow()
    val captureErrors: SharedFlow<Throwable> = _captureErrors.asSharedFlow()
    val captureLevel: StateFlow<Float> = _captureLevel.asStateFlow()
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    init {
        runtime.setListener(
            object : GgwaveRuntime.Listener {
                override fun onReceivedMessage(message: String) {
                    if (message.isNotBlank()) {
                        _receivedMessages.tryEmit(message)
                    }
                }

                override fun onCaptureLevelChanged(level: Float) {
                    _captureLevel.value = level
                }

                override fun onCaptureFailed(error: Throwable) {
                    _isCapturing.value = false
                    _captureLevel.value = 0f
                    _captureErrors.tryEmit(error)
                }

                override fun onPlayEnded() {
                    playEnded?.complete(Unit)
                }

                override fun onPlayFailed(error: Throwable) {
                    playEnded?.completeExceptionally(error)
                }
            },
        )
    }

    fun startCapture() {
        captureLock.withLock {
            checkActive()
            if (_isCapturing.value) return
            _isCapturing.value = true
            try {
                runtime.startCapturing()
            } catch (error: Throwable) {
                _isCapturing.value = false
                throw error
            }
        }
    }

    fun stopCapture() {
        captureLock.withLock {
            stopCaptureLocked()
        }
    }

    private fun stopCaptureLocked() {
        if (!_isCapturing.value) return
        runtime.stopCapturing()
        _isCapturing.value = false
        _captureLevel.value = 0f
    }

    suspend fun send(message: String) {
        checkActive()
        require(message.isNotBlank()) { "message must not be blank" }

        sendMutex.withLock {
            val done = CompletableDeferred<Unit>()
            playEnded = done
            _isPlaying.value = true
            try {
                // Checked after publishing playEnded, so a concurrent close() either sees and cancels it or is seen here.
                checkActive()
                runtime.startPlayback(message)
                withTimeout(PLAYBACK_TIMEOUT_MS) {
                    done.await()
                }
            } catch (error: TimeoutCancellationException) {
                runtime.stopPlayback()
                throw GgwaveException("Playback did not complete", error)
            } finally {
                _isPlaying.value = false
                playEnded = null
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        captureLock.withLock {
            stopCaptureLocked()
        }
        playEnded?.cancel()
        playEnded = null
        try {
            runtime.stopPlayback()
            runtime.close()
        } finally {
            if (ownsProcessSlot) {
                SessionGuard.release()
            }
        }
    }

    private fun checkActive() {
        check(!closed.load()) { "GgwaveSession is closed" }
    }

    companion object {
        private const val PLAYBACK_TIMEOUT_MS = 60_000L

        fun create(config: GgwaveConfig = GgwaveConfig()): GgwaveSession {
            if (!SessionGuard.tryAcquire()) {
                throw GgwaveException(
                    "Only one GgwaveSession can be active at a time. Close the existing session first.",
                )
            }
            return try {
                GgwaveSession(createGgwaveRuntime(config), ownsProcessSlot = true)
            } catch (error: Throwable) {
                SessionGuard.release()
                throw error
            }
        }
    }
}
