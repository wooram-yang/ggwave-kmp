package io.github.moreflow.ggwave.internal

internal interface RuntimeLock {
    fun lock()
    fun unlock()
}

internal expect fun RuntimeLock(): RuntimeLock

internal inline fun <T> RuntimeLock.withLock(action: () -> T): T {
    lock()
    try {
        return action()
    } finally {
        unlock()
    }
}
