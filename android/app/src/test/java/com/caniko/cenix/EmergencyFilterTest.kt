package com.caniko.cenix

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmergencyFilterTest {
    private val user = android.os.Process.myUserHandle()

    private fun app(pkg: String, cls: String, profile: Long, label: String) = LaunchableApp(
        packageName = pkg,
        className = cls,
        profileId = profile,
        label = label,
        normalizedLabel = EmergencyFilter.normalize(label),
        user = user,
        icon = null,
    )

    @Test
    fun rankingAndHiddenProfilesMatchCore() {
        val apps = listOf(
            app("exact.pkg", "E", 0, "Mail"),
            app("prefix.pkg", "P", 0, "Mailbox"),
            app("hidden.pkg", "H", 9, "Mail"),
        )
        val result = EmergencyFilter.filter(apps, "mail", setOf(0L))
        assertEquals(listOf("exact.pkg", "prefix.pkg"), result.map { it.packageName })
    }

    @Test
    fun emptyQuerySortsVisibleApps() {
        val apps = listOf(
            app("b.pkg", "B", 0, "Bravo"),
            app("a.pkg", "A", 0, "Alpha"),
            app("h.pkg", "H", 2, "Hidden"),
        )
        val result = EmergencyFilter.filter(apps, "", setOf(0L))
        assertEquals(listOf("a.pkg", "b.pkg"), result.map { it.packageName })
    }
}
