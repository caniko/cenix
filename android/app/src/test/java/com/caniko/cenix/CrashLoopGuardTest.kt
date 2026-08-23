package com.caniko.cenix

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLoopGuardTest {
    @Test
    fun threeInterruptedStartupsEnterEmergency() {
        var now = 1_000L
        val guard = CrashLoopGuard(MemoryStore(), clock = { now })
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
        val guard = CrashLoopGuard(MemoryStore(), clock = { now })
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
        val guard = CrashLoopGuard(MemoryStore())
        guard.requestEmergency()
        assertFalse(guard.beginStartup())
        guard.clearUserEmergency()
        assertTrue(guard.beginStartup())
    }
}
