package com.caniko.cenix

class CrashLoopGuard(
    private val store: KeyValueStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val threshold: Int = 3,
    private val windowMs: Long = 60_000,
) {
    fun beginStartup(): Boolean {
        if (store.getBoolean(USER_EMERGENCY, false)) return false
        if (store.getBoolean(IN_PROGRESS, false)) {
            recordFailure()
        }
        if (failureCountInWindow() >= threshold) return false
        store.putBoolean(IN_PROGRESS, true)
        return true
    }

    fun markHealthy() {
        store.putBoolean(IN_PROGRESS, false)
        store.putInt(FAILURES, 0)
    }

    fun recordFailure() {
        val now = clock()
        val last = store.getLong(LAST_FAILURE, 0)
        val count = if (now - last > windowMs) 1 else store.getInt(FAILURES, 0) + 1
        store.putInt(FAILURES, count)
        store.putLong(LAST_FAILURE, now)
        store.putBoolean(IN_PROGRESS, false)
    }

    fun requestEmergency() {
        store.putBoolean(USER_EMERGENCY, true)
        store.putBoolean(IN_PROGRESS, false)
    }

    fun clearUserEmergency() {
        store.putBoolean(USER_EMERGENCY, false)
        store.putInt(FAILURES, 0)
        store.putBoolean(IN_PROGRESS, false)
    }

    fun failureCountInWindow(): Int {
        val last = store.getLong(LAST_FAILURE, 0)
        if (clock() - last > windowMs) return 0
        return store.getInt(FAILURES, 0)
    }

    companion object {
        const val IN_PROGRESS = "startup_in_progress"
        const val FAILURES = "startup_failures"
        const val LAST_FAILURE = "startup_last_failure"
        const val USER_EMERGENCY = "user_emergency"
    }
}
