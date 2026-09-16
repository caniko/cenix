package com.caniko.cenix

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SearchController(
    private val filterOf: () -> AppFilter,
    private val onEmergency: () -> Unit,
    private val onResult: (Int, Int, List<LaunchableApp>) -> Unit,
) {
    private val seq = AtomicInteger()
    private val generation = AtomicInteger()
    private val debounce = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "cenix-search").apply { isDaemon = true }
    }
    private var pending: ScheduledFuture<*>? = null

    fun submit(apps: List<LaunchableApp>, query: String, profiles: Set<Long>, delayMs: Long = 50) {
        val token = seq.incrementAndGet()
        val publication = generation.get()
        pending?.cancel(false)
        pending = debounce.schedule({
            CenixExecutors.io {
                if (token != seq.get() || publication != generation.get()) return@io
                val matches = try {
                    filterOf().filter(apps, query, profiles)
                } catch (_: Throwable) {
                    onEmergency()
                    EmergencyAppFilter.filter(apps, query, profiles)
                }
                if (token == seq.get() && publication == generation.get()) onResult(publication, token, matches)
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    fun cancel() {
        seq.incrementAndGet()
        pending?.cancel(false)
    }

    // Profile lock/removal and reloads invalidate pending results so a stale search
    // computed against the old catalog can never republish private content.
    fun invalidate() {
        generation.incrementAndGet()
        cancel()
    }

    // The UI thread re-verifies both the lifecycle generation and the request
    // sequence carried with each result: a newer query in the same generation,
    // or invalidation between background dispatch and UI delivery, drops it.
    fun isCurrent(publication: Int, token: Int): Boolean =
        publication == generation.get() && token == seq.get()

    fun close() {
        generation.incrementAndGet()
        cancel()
        debounce.shutdownNow()
    }
}
