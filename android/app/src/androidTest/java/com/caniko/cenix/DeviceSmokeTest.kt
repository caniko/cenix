package com.caniko.cenix

import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.caniko.cenix.uniffi.ProfileAccess
import com.caniko.cenix.uniffi.ProfileKind
import com.caniko.cenix.uniffi.classifyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// First-pass device smoke suite: launches HomeActivity directly, never
// presses HOME, never changes the default launcher. Run only via
// scripts/device-test.sh, which installs the app plus the explicitly
// allowed fixture packages into the test user first.
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun expectedCommit(): String =
        InstrumentationRegistry.getArguments().getString("gitCommit")
            ?: throw AssertionError("missing -e gitCommit instrumentation arg")

    @Test
    fun nativeStartupAndBuildIdentity() {
        // Real FFI call through the packaged native library: the owner
        // profile must classify personal/available. Emergency fallback or a
        // missing library fails here instead of silently passing.
        val descriptor = classifyProfile(0UL, true, null, false, true, true, null)
        assertEquals(ProfileKind.PERSONAL, descriptor.kind)
        assertEquals(ProfileAccess.AVAILABLE, descriptor.access)
        assertEquals(expectedCommit(), BuildConfig.GIT_COMMIT)
        // Explicit cross-package intent: ActivityScenario.launch(Class)
        // resolves against the test package, not the app under test.
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(PKG, "$PKG.HomeActivity")
        }
        ActivityScenario.launch<HomeActivity>(homeIntent).use { scenario ->
            scenario.onActivity { activity ->
                val app = activity.application as CenixApplication
                assertTrue(app.awaitReady())
                assertFalse(app.emergency)
            }
            assertTrue(device.wait(Until.hasObject(By.res(PKG, "homeSurface")), 20_000))
            assertTrue(device.hasObject(By.res(PKG, "workspaceGrid")))
            assertFalse(device.hasObject(By.res(PKG, "retryNative")))
        }
    }

    @Test
    fun drawerSearchBackAndSettings() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(PKG, "$PKG.HomeActivity")
        }
        ActivityScenario.launch<HomeActivity>(homeIntent).use {
            assertTrue("launcherRoot never appeared", device.wait(Until.hasObject(By.res(PKG, "launcherRoot")), 10_000))
            openDrawer()
            val field = device.findObject(By.res(PKG, "searchField"))
            field.click()
            field.setText("fixture")
            assertTrue("fixture row never appeared", device.wait(Until.hasObject(By.res(PKG, "appLabel").text("Cenix Fixture")), 5_000))
            device.pressBack()
            assertTrue("search still visible after back", device.wait(Until.gone(By.res(PKG, "searchField")), 5_000))
            openDrawer()
            val entry = device.wait(Until.findObject(By.res(PKG, "launcherSettings")), 5_000)
            assertNotNull("settings entry missing", entry)
            entry.click()
            assertTrue("settings never opened", device.wait(Until.hasObject(By.res(PKG, "settingsTitle")), 5_000))
            device.pressBack()
            assertTrue("launcher gone after settings back", device.wait(Until.hasObject(By.res(PKG, "launcherRoot")), 5_000))
        }
    }

    @Test
    fun fixtureLaunchAndReturn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Explicit component: getLaunchIntentForPackage is subject to package
        // visibility filtering and returns null without a <queries> entry,
        // which would fake a missing-fixture failure.
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(PKG, "$PKG.HomeActivity")
        }
        ActivityScenario.launch<HomeActivity>(homeIntent).use {
            assertTrue(device.wait(Until.hasObject(By.res(PKG, "launcherRoot")), 10_000))
            val fixtureIntent = Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(FIXTURE_PKG, "$FIXTURE_PKG.FixtureActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fixtureIntent)
            assertTrue(device.wait(Until.hasObject(By.pkg(FIXTURE_PKG)), 10_000))
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.res(PKG, "launcherRoot")), 10_000))
        }
    }

    private fun openDrawer() {
        val bounds = device.findObject(By.res(PKG, "launcherRoot")).visibleBounds
        device.swipe(
            bounds.centerX(), bounds.top + bounds.height() * 3 / 4,
            bounds.centerX(), bounds.top + bounds.height() / 4, 20,
        )
        assertTrue(device.wait(Until.hasObject(By.res(PKG, "searchField")), 5_000))
    }

    companion object {
        private const val PKG = "com.caniko.cenix"
        private const val FIXTURE_PKG = "com.caniko.cenix.fixture"
    }
}
