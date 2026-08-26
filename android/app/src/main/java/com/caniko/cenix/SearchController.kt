package com.caniko.cenix

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SearchController(
    private val filterOf: () -> AppFilter,
    private val onEmergency: () -> Unit,
    private val onResult: (List<LaunchableApp>) -> Unit,
) {
    private val seq = AtomicInteger()
    private val debounce = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "cenix-search").apply { isDaemon = true }
    }
    private var pending: ScheduledFuture<*>? = null

    fun submit(apps: List<LaunchableApp>, query: String, profiles: Set<Long>, delayMs: Long = 50) {
        val token = seq.incrementAndGet()
        pending?.cancel(false)
        pending = debounce.schedule({
            CenixExecutors.io {
                if (token != seq.get()) return@io
                val matches = try {
                    filterOf().filter(apps, query, profiles)
                } catch (_: Throwable) {
                    onEmergency()
                    EmergencyAppFilter.filter(apps, query, profiles)
                }
                if (token == seq.get()) onResult(matches)
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    fun cancel() {
        seq.incrementAndGet()
        pending?.cancel(false)
    }

    fun close() {
        cancel()
        debounce.shutdownNow()
    }
}
