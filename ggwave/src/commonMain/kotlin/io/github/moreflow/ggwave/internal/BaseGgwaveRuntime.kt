package io.github.moreflow.ggwave.internal

import io.github.moreflow.ggwave.GgwaveCodecConfig
import io.github.moreflow.ggwave.GgwaveConfig
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** State shared by the platform runtimes; subclasses only implement audio I/O. */
internal abstract class BaseGgwaveRuntime(
    protected val config: GgwaveConfig,
) : GgwaveRuntime {
    protected val codecs = GgwaveRuntimeCodecs.create(
        GgwaveCodecConfig(config.sampleRate, config.sampleRate),
    )
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    protected var listener: GgwaveRuntime.Listener = object : GgwaveRuntime.Listener {}
        private set

    override fun setListener(listener: GgwaveRuntime.Listener) {
        this.listener = listener
    }

    override fun close() {
        stopCapturing()
        stopPlayback()
        scope.cancel()
        codecs.close()
    }
}
