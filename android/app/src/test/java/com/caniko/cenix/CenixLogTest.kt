package com.caniko.cenix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CenixLogTest {
    @Test
    fun releaseRedactsPackageAndQuery() {
        CenixLog.redact = true
        val line = CenixLog.format(
            EventId.CATALOG_REFRESH,
            Severity.INFO,
            mapOf("package" to "com.android.settings", "query" to "set", "count" to "3"),
        )
        assertFalse(line.contains("com.android.settings"))
        assertFalse(line.contains("set") && line.contains("query=set"))
        assertTrue(line.contains("package=[redacted]"))
        assertTrue(line.contains("query=[redacted]"))
        assertTrue(line.contains("count=3"))
    }

    @Test
    fun debugKeepsIdentities() {
        CenixLog.redact = false
        val line = CenixLog.format(
            EventId.PACKAGE_CALLBACK,
            Severity.INFO,
            mapOf("package" to "com.caniko.cenix.fixture", "label" to "Fixture"),
        )
        assertTrue(line.contains("com.caniko.cenix.fixture"))
        assertEquals("Fixture", CenixLog.sanitize("label", "Fixture"))
        CenixLog.redact = true
    }
}
