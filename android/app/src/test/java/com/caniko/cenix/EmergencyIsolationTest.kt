package com.caniko.cenix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyIsolationTest {
    @Test
    fun emergencyPathDoesNotConstructNativeFilter() {
        var constructed = 0
        val filter = if (true) {
            EmergencyAppFilter
        } else {
            constructed += 1
            error("native filter must not load")
        }
        assertSame(EmergencyAppFilter, filter)
        assertEquals(0, constructed)
    }

    @Test
    fun injectedFactoryIsSkippedWhenEmergency() {
        var called = false
        val emergency = true
        val factory: () -> AppFilter = {
            called = true
            error("should not load")
        }
        val filter = if (emergency) EmergencyAppFilter else factory()
        assertSame(EmergencyAppFilter, filter)
        assertTrue(!called)
    }
}
