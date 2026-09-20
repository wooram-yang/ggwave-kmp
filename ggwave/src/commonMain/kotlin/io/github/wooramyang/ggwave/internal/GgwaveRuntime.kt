package io.github.wooramyang.ggwave.internal

import io.github.wooramyang.ggwave.GgwaveConfig

internal interface GgwaveRuntime {
    fun startCapturing()
    fun stopCapturing()
    fun startPlayback(message: String)
    fun stopPlayback()
    fun setListeners(
        onReceivedMessage: (String) -> Unit,
        onPlayEnded: () -> Unit,
        onPlayFailed: (Throwable) -> Unit,
    )
    fun close()
}

internal expect fun createGgwaveRuntime(config: GgwaveConfig): GgwaveRuntime
