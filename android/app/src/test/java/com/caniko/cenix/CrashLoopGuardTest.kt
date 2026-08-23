package com.caniko.cenix

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLoopGuardTest {
    @Test
    fun threeInterruptedStartupsEnterEmergency() {
        var now = 1_000L
        val guard = CrashLoopGuard(MemoryStartupStore(), clock = { now })
        assertTrue(guard.beginStartup())
        now += 1
        assertTrue(guard.beginStartup())
        now += 1
        assertTrue(guard.beginStartup())
        now += 1
        assertFalse(guard.beginStartup())
    }

    @Test
    fun healthyStartupResetsTheWindow() {
        var now = 1_000L
        val guard = CrashLoopGuard(MemoryStartupStore(), clock = { now })
        assertTrue(guard.beginStartup())
        guard.markHealthy()
        now += 1
        assertTrue(guard.beginStartup())
        assertTrue(guard.beginStartup())
        guard.markHealthy()
        now += 1
        assertTrue(guard.beginStartup())
    }

    @Test
    fun userEmergencySticksUntilCleared() {
        val guard = CrashLoopGuard(MemoryStartupStore())
        guard.requestEmergency()
        assertFalse(guard.beginStartup())
        guard.clearEmergency()
        assertTrue(guard.beginStartup())
    }

    @Test
    fun crashLoopDoesNotPersistEmergency() {
        var now = 1_000L
        val store = MemoryStartupStore()
        val first = CrashLoopGuard(store, clock = { now })
        assertTrue(first.beginStartup())
        now += 1
        assertTrue(first.beginStartup())
        now += 1
        assertTrue(first.beginStartup())
        now += 1
        assertFalse(first.beginStartup())
        assertFalse(first.isEmergency())
        val second = CrashLoopGuard(store, clock = { now })
        assertFalse(second.isEmergency())
        assertFalse(second.beginStartup())
    }

    @Test
    fun expiredFailuresDoNotPoison() {
        var now = 1_000L
        val store = MemoryStartupStore()
        val guard = CrashLoopGuard(store, clock = { now })
        assertTrue(guard.beginStartup())
        now += 1
        assertTrue(guard.beginStartup())
        now += 1
        assertTrue(guard.beginStartup())
        now += 1
        assertFalse(guard.beginStartup())
        now += 61_000
        assertTrue(guard.beginStartup())
        assertFalse(guard.isEmergency())
    }

    @Test
    fun markHealthyKeepsUserEmergency() {
        val guard = CrashLoopGuard(MemoryStartupStore())
        guard.requestEmergency()
        guard.markHealthy()
        assertTrue(guard.isEmergency())
        assertFalse(guard.beginStartup())
    }
}
