package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveConfig

internal interface GgwaveRuntime {
    fun startCapturing()
    fun stopCapturing()
    fun startPlayback(message: String)
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
