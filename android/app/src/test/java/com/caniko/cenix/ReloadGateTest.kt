package com.caniko.cenix

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReloadGateTest {
    @Test
    fun onlyLatestReloadMayPublish() {
        val gate = ReloadGate()
        val stale = gate.next()
        val current = gate.next()
        assertFalse(gate.isCurrent(stale))
        assertTrue(gate.isCurrent(current))
    }
}
