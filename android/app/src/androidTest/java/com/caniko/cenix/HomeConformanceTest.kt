package com.caniko.cenix

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeConformanceTest {
    private lateinit var device: UiDevice

    @Before
    fun openHome() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressHome()
        assertTrue(device.wait(Until.hasObject(By.res(PKG, "homeSurface")), 20_000))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CenixApplication
        assertTrue(app.awaitReady())
        assertEquals(BuildConfig.GIT_COMMIT, app.let { BuildConfig.GIT_COMMIT })
        assertTrue(BuildConfig.GIT_COMMIT.isNotBlank())
    }

    @Test
    fun initialSurfaceIsHomeOnly() {
        assertTrue(device.hasObject(By.res(PKG, "workspaceGrid")))
        assertTrue(device.hasObject(By.res(PKG, "hotseatGrid")))
        assertFalse(device.hasObject(By.res(PKG, "searchField")))
        assertFalse(device.hasObject(By.res(PKG, "appList")))
        assertFalse(device.hasObject(By.res(PKG, "retryNative")))
    }

    @Test
    fun allAppsSearchBackSwipeAndHomeTransitions() {
        openAllApps()
        val field = device.findObject(By.res(PKG, "searchField"))
        field.click()
        field.setText("fixture")
        assertTrue(device.wait(Until.hasObject(By.res(PKG, "appLabel").text("Cenix Fixture")), 5_000))

        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.res(PKG, "searchField")), 5_000))
        device.pressBack()
        assertTrue(device.wait(Until.gone(By.res(PKG, "searchField")), 5_000))

        openAllApps()
        swipeRoot(up = false)
        assertTrue(device.wait(Until.gone(By.res(PKG, "searchField")), 5_000))

        openAllApps()
        device.pressHome()
        assertTrue(device.wait(Until.gone(By.res(PKG, "searchField")), 5_000))
        assertTrue(device.hasObject(By.res(PKG, "workspaceGrid")))
    }

    @Test
    fun allAppsLongPressShowsTransientContextActions() {
        openAllApps()
        val field = device.findObject(By.res(PKG, "searchField"))
        field.click()
        field.setText("fixture")
        val app = device.wait(Until.findObject(By.res(PKG, "appLabel").text("Cenix Fixture")), 5_000)
        assertTrue(app != null)
        val bounds = app.visibleBounds
        device.executeShellCommand("input touchscreen swipe ${bounds.centerX()} ${bounds.centerY()} ${bounds.centerX()} ${bounds.centerY()} 800")
        assertTrue(device.wait(Until.hasObject(By.res(PKG, "context_popup")), 5_000))
        assertTrue(device.hasObject(By.text("Manifest action")))
        assertTrue(device.hasObject(By.text("Dynamic action")))
        device.pressBack()
        assertTrue(device.wait(Until.gone(By.res(PKG, "context_popup")), 5_000))
    }

    private fun openAllApps() {
        swipeRoot(up = true)
        assertTrue(device.wait(Until.hasObject(By.res(PKG, "searchField")), 5_000))
    }

    private fun swipeRoot(up: Boolean) {
        val bounds = device.findObject(By.res(PKG, "launcherRoot")).visibleBounds
        val startY = if (up) bounds.top + bounds.height() * 3 / 4 else bounds.top + bounds.height() / 4
        val endY = if (up) bounds.top + bounds.height() / 4 else bounds.top + bounds.height() * 3 / 4
        device.swipe(bounds.centerX(), startY, bounds.centerX(), endY, 30)
    }

    companion object {
        private const val PKG = "com.caniko.cenix"
    }
}
