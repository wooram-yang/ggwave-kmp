package io.github.moreflow.ggwave.internal

import java.util.concurrent.locks.ReentrantLock

internal actual fun RuntimeLock(): RuntimeLock = object : RuntimeLock {
    private val delegate = ReentrantLock()

    override fun lock() = delegate.lock()

    override fun unlock() = delegate.unlock()
}
