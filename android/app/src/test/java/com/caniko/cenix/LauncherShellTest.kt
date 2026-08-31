package com.caniko.cenix

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherShellTest {
    @Test
    fun swipeUsesDistanceOrVelocityThreshold() {
        assertEquals(LauncherSurface.HOME, LauncherShell.swipeTarget(LauncherSurface.HOME, -20f, -100f, 640))
        assertEquals(LauncherSurface.ALL_APPS, LauncherShell.swipeTarget(LauncherSurface.HOME, -100f, -100f, 640))
        assertEquals(LauncherSurface.ALL_APPS, LauncherShell.swipeTarget(LauncherSurface.HOME, -20f, -900f, 640))
        assertEquals(LauncherSurface.HOME, LauncherShell.swipeTarget(LauncherSurface.ALL_APPS, 100f, 100f, 640))
    }

    @Test
    fun backClosesImeBeforeAllApps() {
        assertEquals(LauncherSurface.ALL_APPS, LauncherShell.backTarget(LauncherSurface.ALL_APPS, true))
        assertEquals(LauncherSurface.HOME, LauncherShell.backTarget(LauncherSurface.ALL_APPS, false))
        assertEquals(LauncherSurface.EMERGENCY, LauncherShell.backTarget(LauncherSurface.EMERGENCY, false))
    }

    @Test
    fun homeIntentAndRtlPageDirectionAreDeterministic() {
        assertEquals(LauncherSurface.HOME, LauncherShell.homeIntent(false))
        assertEquals(LauncherSurface.EMERGENCY, LauncherShell.homeIntent(true))
        assertEquals(1, LauncherShell.pageDelta(swipeLeft = true, rtl = false))
        assertEquals(-1, LauncherShell.pageDelta(swipeLeft = true, rtl = true))
    }
}
