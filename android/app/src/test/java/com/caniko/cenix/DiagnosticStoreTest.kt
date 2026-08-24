package com.caniko.cenix

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory

class DiagnosticStoreTest {
    @Test
    fun exportIncludesIdentityAndEvents() {
        val tmp = createTempDirectory("cenix-diag").toFile()
        DiagnosticStore.init(tmp)
        File(tmp, "diag").mkdirs()
        File(tmp, "diag/events.0.log").writeText("INFO STARTUP\n")
        val out = ByteArrayOutputStream()
        DiagnosticStore.export(out, mapOf("commit" to "deadbeef", "schema" to "2"))
        val text = out.toString()
        assertTrue(text.contains("commit=deadbeef"))
        assertTrue(text.contains("schema=2"))
        assertTrue(text.contains("INFO STARTUP"))
    }
}
