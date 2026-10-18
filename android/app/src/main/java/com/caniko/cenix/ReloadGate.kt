package com.caniko.cenix

import java.util.concurrent.atomic.AtomicInteger

// Drops obsolete catalog reloads: only the latest scheduled reload may publish UI.
internal class ReloadGate {
    private val current = AtomicInteger(0)
    @Volatile private var closed = false

    fun next(): Int = current.incrementAndGet()

    fun isOpen(): Boolean = !closed

    fun isCurrent(token: Int): Boolean = isOpen() && token == current.get()

    fun close() {
        closed = true
    }
}
