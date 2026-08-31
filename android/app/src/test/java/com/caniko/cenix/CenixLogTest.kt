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

    @Test
    fun releaseProfileEventsKeepCategoriesAndRedactIdentities() {
        CenixLog.redact = true
        val line = CenixLog.format(
            EventId.PRIVATE_LOCKED,
            Severity.INFO,
            mapOf(
                "kind" to "PRIVATE",
                "state" to "LOCKED",
                "profileSerial" to "42",
                "userId" to "11",
                "shortcutId" to "private-secret",
                "widgetProvider" to "private.widget/.Provider",
            ),
        )
        assertTrue(line.contains("kind=PRIVATE"))
        assertTrue(line.contains("state=LOCKED"))
        assertFalse(line.contains("42"))
        assertFalse(line.contains("userId=11"))
        assertFalse(line.contains("private-secret"))
        assertFalse(line.contains("private.widget"))
    }
}
