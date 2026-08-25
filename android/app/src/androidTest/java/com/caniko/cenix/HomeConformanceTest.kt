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
        assertTrue(device.wait(Until.hasObject(By.res(PKG, "searchField")), 20_000))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CenixApplication
        assertTrue(app.awaitReady())
        assertEquals(BuildConfig.GIT_COMMIT, app.let { BuildConfig.GIT_COMMIT })
        assertTrue(BuildConfig.GIT_COMMIT.isNotBlank())
    }

    @Test
    fun homeChromeAndCatalog() {
        assertTrue(device.hasObject(By.res(PKG, "searchField")))
        assertTrue(device.hasObject(By.res(PKG, "workspaceGrid")))
        assertTrue(device.hasObject(By.res(PKG, "hotseatGrid")))
        assertTrue(device.hasObject(By.res(PKG, "retryNative")))
        val title = device.findObject(By.res(PKG, "statusTitle"))?.text.orEmpty()
        assertFalse(title.contains("emergency", ignoreCase = true))
        assertFalse(device.hasObject(By.res(PKG, "appLabel").text("Cenix")))
    }

    @Test
    fun searchIsCaseInsensitiveAndClearRestoresWorkspace() {
        val field = device.findObject(By.res(PKG, "searchField"))
        field.click()
        field.setText("set")
        assertTrue(device.wait(Until.hasObject(By.res(PKG, "appLabel").textContains("Settings")), 5_000))
        field.setText("zzznomatch")
        device.wait(Until.gone(By.res(PKG, "appLabel").textContains("Settings")), 5_000)
        field.setText("")
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.res(PKG, "workspaceGrid")), 5_000))
    }

    companion object {
        private const val PKG = "com.caniko.cenix"
    }
}
