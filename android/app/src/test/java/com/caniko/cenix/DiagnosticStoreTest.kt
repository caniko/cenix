package com.caniko.cenix

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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

    @Test
    fun releaseFormattingRedactsEveryLauncherIdentityClass() {
        CenixLog.redact = true
        val text = CenixLog.format(
            EventId.PACKAGE_CALLBACK,
            Severity.INFO,
            mapOf(
                "package" to "pkg",
                "class" to "Main",
                "label" to "App",
                "shortcut" to "dynamic",
                "widgetProvider" to "Clock",
                "appWidget" to "42",
                "profileSerial" to "10",
                "user" to "0",
                "session" to "7",
                "folder" to "Tools",
            ),
        )
        assertFalse(text.contains("pkg"))
        assertFalse(text.contains("Main"))
        assertFalse(text.contains("dynamic"))
        assertFalse(text.contains("Clock"))
        assertFalse(text.contains("Tools"))
    }

    @Test
    fun appendNeverThrowsWhenDiagDirIsUnwritable() {
        // Seen once as ENOENT on first launch in a fresh profile: mkdirs can
        // fail (here forced by making the parent a regular file), and the
        // background write must drop the line instead of crashing the app.
        val tmp = createTempDirectory("cenix-diag-ro").toFile()
        val blocker = File(tmp, "blocker")
        blocker.writeText("x")
        DiagnosticStore.init(blocker)
        val thrown = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> thrown.add(e) }
        try {
            DiagnosticStore.append("INFO TEST")
            val done = java.util.concurrent.CountDownLatch(1)
            // Single-thread executor: the sentinel runs after the append
            // task, so completion proves it finished without escaping.
            CenixExecutors.io.execute { done.countDown() }
            assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(thrown.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(prior)
        }
    }

    @Test
    fun resetRemovesEarlierDiagnosticEvents() {
        val tmp = createTempDirectory("cenix-diag-reset").toFile()
        DiagnosticStore.init(tmp)
        File(tmp, "diag").mkdirs()
        File(tmp, "diag/events.0.log").writeText("old identity\n")
        DiagnosticStore.reset()
        val out = ByteArrayOutputStream()
        DiagnosticStore.export(out, emptyMap())
        assertFalse(out.toString().contains("old identity"))
    }
}
