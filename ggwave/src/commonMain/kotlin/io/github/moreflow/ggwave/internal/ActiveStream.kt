package io.github.moreflow.ggwave.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Owns the coroutine that drives one capture or playback stream and the audio resource it uses.
 *
 * The resource is released exactly once: by [stop], or when the stream's coroutine finishes.
 */
internal class ActiveStream<T : Any>(
    private val release: (T) -> Unit = {},
) {
    private val lock = RuntimeLock()
    private var job: Job? = null
    private var resource: T? = null

    /**
     * Launches [block] as the current stream. [onFailure] receives errors thrown while the
     * stream is still active; errors caused by [stop] are ignored.
     */
    fun start(
        scope: CoroutineScope,
        resource: T? = null,
        onFailure: (Throwable) -> Unit,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (isActive) {
                    onFailure(error)
                }
            } finally {
                finish(coroutineContext.job)
            }
        }
        lock.withLock {
            this.job = job
            this.resource = resource
        }
        job.start()
    }

    /**
     * Hands a resource created inside the stream's coroutine over to this stream.
     * If the stream was stopped in the meantime, [resource] is released and the coroutine is cancelled.
     */
    suspend fun attach(resource: T) {
        val job = currentCoroutineContext().job
        val attached = lock.withLock {
            (this.job === job).also { current ->
                if (current) this.resource = resource
            }
        }
        if (!attached) {
            release(resource)
            throw CancellationException("Stream was stopped")
        }
    }

    fun stop() {
        val (job, resource) = lock.withLock {
            val current = job to resource
            this.job = null
            this.resource = null
            current
        }
        job?.cancel()
        resource?.let(release)
    }

    private fun finish(job: Job) {
        val resource = lock.withLock {
            if (this.job !== job) return
            this.job = null
            this.resource.also { this.resource = null }
        }
        resource?.let(release)
    }
}
