package com.caniko.cenix

import java.util.concurrent.atomic.AtomicInteger

// Drops obsolete catalog reloads: only the latest scheduled reload may publish UI.
internal class ReloadGate {
    private val current = AtomicInteger(0)

    fun next(): Int = current.incrementAndGet()

    fun isCurrent(token: Int): Boolean = token == current.get()
}
