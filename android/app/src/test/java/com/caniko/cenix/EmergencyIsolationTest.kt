package com.caniko.cenix

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CenixApplication::class)
class EmergencyIsolationTest {
    @Test
    fun emergencyPathDoesNotConstructNativeFilter() {
        val app = ApplicationProvider.getApplicationContext<CenixApplication>()
        var called = false
        app.emergency = true
        app.filterFactory = {
            called = true
            error("native filter must not load")
        }
        assertSame(EmergencyAppFilter, app.activeFilter())
        assertFalse(called)
    }

    @Test
    fun factoryFailureEntersEmergency() {
        val app = ApplicationProvider.getApplicationContext<CenixApplication>()
        app.emergency = false
        app.filterFactory = { error("missing libcenix_ffi.so") }
        assertSame(EmergencyAppFilter, app.activeFilter())
        assertTrue(app.emergency)
        assertTrue(app.crashLoop.isEmergency())
    }

    @Test
    fun probeFailureEntersEmergency() {
        val app = ApplicationProvider.getApplicationContext<CenixApplication>()
        app.emergency = false
        app.filterFactory = {
            AppFilter { _, _, _ -> error("uniffi load failed") }
        }
        assertSame(EmergencyAppFilter, app.activeFilter())
        assertTrue(app.emergency)
    }

    @Test
    fun retryNativeStaysEmergencyWhenProbeFails() {
        val app = ApplicationProvider.getApplicationContext<CenixApplication>()
        app.requestEmergency()
        app.filterFactory = { error("still missing") }
        assertFalse(app.retryNative())
        assertTrue(app.emergency)
        assertSame(EmergencyAppFilter, app.activeFilter())
    }

    @Test
    fun retryNativeClearsEmergencyAfterProbeSucceeds() {
        val app = ApplicationProvider.getApplicationContext<CenixApplication>()
        app.requestEmergency()
        app.filterFactory = { AppFilter { apps, _, _ -> apps } }
        assertTrue(app.retryNative())
        assertFalse(app.emergency)
        assertFalse(app.crashLoop.isEmergency())
    }

    @Test
    fun resetClearsPersistedEmergency() {
        val app = ApplicationProvider.getApplicationContext<CenixApplication>()
        assertTrue(app.awaitReady())
        app.requestEmergency()
        app.resetLocalState()
        assertFalse(app.emergency)
        assertFalse(app.crashLoop.isEmergency())
    }
}
