package com.caniko.cenix

data class StartupState(
    val inProgress: Boolean = false,
    val failures: Int = 0,
    val lastFailureAt: Long = 0,
    val emergency: Boolean = false,
)

interface StartupStore {
    fun load(): StartupState
    fun save(state: StartupState)
}

class MemoryStartupStore : StartupStore {
    private var state = StartupState()
    override fun load() = state
    override fun save(state: StartupState) {
        this.state = state
    }
}

class CrashLoopGuard(
    private val store: StartupStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val threshold: Int = 3,
    private val windowMs: Long = 60_000,
) {
    fun beginStartup(): Boolean {
        var state = store.load()
        if (state.emergency) return false
        if (state.inProgress) {
            state = recordFailure(state)
        }
        if (failureCountInWindow(state) >= threshold) {
            store.save(state)
            return false
        }
        store.save(state.copy(inProgress = true))
        return true
    }

    fun markHealthy() {
        store.save(store.load().copy(inProgress = false, failures = 0))
    }

    fun requestEmergency() {
        store.save(store.load().copy(emergency = true, inProgress = false))
    }

    fun clearEmergency() {
        store.save(StartupState())
    }

    fun isEmergency(): Boolean = store.load().emergency

    private fun recordFailure(state: StartupState): StartupState {
        val now = clock()
        val count = if (now - state.lastFailureAt > windowMs) 1 else state.failures + 1
        return state.copy(failures = count, lastFailureAt = now, inProgress = false)
    }

    private fun failureCountInWindow(state: StartupState): Int {
        if (clock() - state.lastFailureAt > windowMs) return 0
        return state.failures
    }
}
