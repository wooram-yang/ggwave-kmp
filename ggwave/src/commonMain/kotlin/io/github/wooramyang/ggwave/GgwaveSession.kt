package io.github.wooramyang.ggwave

import io.github.wooramyang.ggwave.internal.GgwaveRuntime
import io.github.wooramyang.ggwave.internal.SessionGuard
import io.github.wooramyang.ggwave.internal.createGgwaveRuntime
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

class GgwaveSession internal constructor(
    private val runtime: GgwaveRuntime,
    private val ownsProcessSlot: Boolean = false,
) {
    private val sendMutex = Mutex()
    private val _receivedMessages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val _isCapturing = MutableStateFlow(false)
    private val _isPlaying = MutableStateFlow(false)

    private var playEnded: CompletableDeferred<Unit>? = null
    private var closed = false

    val receivedMessages: SharedFlow<String> = _receivedMessages.asSharedFlow()
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    init {
        runtime.setListeners(
            onReceivedMessage = { message ->
                if (message.isNotBlank()) {
                    _receivedMessages.tryEmit(message)
                }
            },
            onPlayEnded = {
                playEnded?.complete(Unit)
            },
            onPlayFailed = { error ->
                playEnded?.completeExceptionally(error)
            },
        )
    }

    fun startCapture() {
        checkActive()
        if (_isCapturing.value) return
        runtime.startCapturing()
        _isCapturing.value = true
    }

    fun stopCapture() {
        if (!_isCapturing.value) return
        runtime.stopCapturing()
        _isCapturing.value = false
    }

    suspend fun send(message: String) {
        checkActive()
        val text = message.trim()
        require(text.isNotEmpty()) { "message must not be blank" }

        sendMutex.withLock {
            val done = CompletableDeferred<Unit>()
            playEnded = done
            _isPlaying.value = true
            try {
                runtime.startPlayback(text)
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
        if (closed) return
        closed = true
        if (_isCapturing.value) {
            runtime.stopCapturing()
            _isCapturing.value = false
        }
        playEnded?.cancel()
        playEnded = null
        runtime.stopPlayback()
        runtime.close()
        if (ownsProcessSlot) {
            SessionGuard.release()
        }
    }

    private fun checkActive() {
        check(!closed) { "GgwaveSession is closed" }
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
