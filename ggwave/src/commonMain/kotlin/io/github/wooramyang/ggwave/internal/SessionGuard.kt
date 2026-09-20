package io.github.wooramyang.ggwave.internal

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
internal object SessionGuard {
    private val held = AtomicBoolean(false)

    fun tryAcquire(): Boolean = held.compareAndSet(expectedValue = false, newValue = true)

    fun release() {
        held.store(false)
    }
}
