package io.github.moreflow.ggwave.internal

import platform.Foundation.NSLock

internal actual fun RuntimeLock(): RuntimeLock = object : RuntimeLock {
    private val delegate = NSLock()

    override fun lock() = delegate.lock()

    override fun unlock() = delegate.unlock()
}
