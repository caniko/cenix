package com.caniko.cenix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FilterProtocolTest {
    @Test
    fun encodeThenDecodeRoundTrip() {
        val app = LaunchableApp(
            "pkg",
            "Cls",
            0,
            "Label",
            "label",
            android.os.Process.myUserHandle(),
            null,
        )
        val bytes = FilterProtocol.encodeRequest("r1", "la", setOf(0L), listOf(app))
        val json = String(bytes, Charsets.UTF_8)
        assertTrue(json.contains("\"request_id\":\"r1\""))
        val outcome = FilterProtocol.decodeResponse(
            """{"protocol_version":1,"request_id":"r1","ok":true,"matches":[{"package":"pkg","class":"Cls","profile_id":0}]}"""
                .toByteArray(),
        )
        assertTrue(outcome.ok)
        assertEquals("pkg", outcome.matches.single().packageName)
    }

    @Test
    fun nullBytesAreFailure() {
        val outcome = FilterProtocol.decodeResponse(null)
        assertFalse(outcome.ok)
        assertEquals("NULL", outcome.errorCode)
    }
}
