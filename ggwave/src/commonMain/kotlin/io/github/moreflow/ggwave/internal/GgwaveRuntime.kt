package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveConfig
import io.github.moreflow.ggwave.GgwaveProtocol

internal interface GgwaveRuntime {
    fun startCapturing()
    fun stopCapturing()
    fun startPlayback(message: String, protocol: GgwaveProtocol)
    fun stopPlayback()
    fun setListener(listener: Listener)
    fun close()

    interface Listener {
        fun onReceivedMessage(message: String) {}
        fun onCaptureLevelChanged(level: Float) {}
        fun onCaptureFailed(error: Throwable) {}
        fun onPlayEnded() {}
        fun onPlayFailed(error: Throwable) {}
    }
}

internal expect fun createGgwaveRuntime(config: GgwaveConfig): GgwaveRuntime
