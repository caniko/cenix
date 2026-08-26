package com.caniko.cenix

import com.caniko.cenix.uniffi.ProfileKind
import com.caniko.cenix.uniffi.ShortcutId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageStateTest {
    @Test
    fun onlyReadyAppsCanBePlacedAndArchivedAppsCanRequestRestore() {
        assertTrue(app(PackageState.READY).canPlace)
        assertTrue(app(PackageState.READY).canLaunch)
        assertFalse(app(PackageState.UPDATING).canPlace)
        assertFalse(app(PackageState.SUSPENDED).canLaunch)
        assertTrue(app(PackageState.ARCHIVED).canLaunch)
        assertFalse(app(PackageState.ARCHIVED).canPlace)
        assertFalse(app(PackageState.TEMPORARILY_UNAVAILABLE).canLaunch)
    }

    @Test
    fun temporaryPackageStatesRetainShortcutsUntilDefinitiveRemoval() {
        val shortcut = ShortcutId("pkg", "dynamic", 0UL)
        assertEquals(listOf(shortcut), retainedShortcutIds(listOf(shortcut), listOf(app(PackageState.SUSPENDED))))
        assertTrue(retainedShortcutIds(listOf(shortcut), listOf(app(PackageState.READY))).isEmpty())
        assertTrue(retainedShortcutIds(listOf(shortcut), emptyList()).isEmpty())
    }

    private fun app(state: PackageState) = LaunchableApp(
        "pkg",
        "Main",
        0,
        ProfileKind.PERSONAL,
        "App",
        "app",
        null,
        null,
        state,
    )
}
