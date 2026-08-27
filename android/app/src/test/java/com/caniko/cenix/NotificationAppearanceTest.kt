package com.caniko.cenix

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import com.caniko.cenix.uniffi.ProfileKind
import com.caniko.cenix.uniffi.ShortcutId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationAppearanceTest {
    @After
    fun clearDots() {
        NotificationDotStore.setEnabled(true)
        NotificationDotStore.replace(emptyMap())
        NotificationDotStore.setRefresher(null)
    }

    @Test
    fun dotsAreProfileScopedAndClearWhenDisabled() {
        val personal = PackageKey("pkg", 0)
        val work = PackageKey("pkg", 10)
        NotificationDotStore.replace(mapOf(personal to NotificationDot(1, setOf("chat")), work to NotificationDot(2, emptySet())))
        assertEquals(1, NotificationDotStore.dot(personal)?.count)
        assertEquals(2, NotificationDotStore.dot(work)?.count)

        NotificationDotStore.removeProfiles(setOf(10))
        assertNull(NotificationDotStore.dot(work))
        NotificationDotStore.setEnabled(false)
        assertNull(NotificationDotStore.dot(personal))
    }

    @Test
    fun pinnedShortcutRequiresMatchingNotificationShortcutId() {
        NotificationDotStore.replace(mapOf(PackageKey("pkg", 0) to NotificationDot(1, setOf("chat"))))
        assertEquals(1, NotificationDotStore.dot(shortcut("chat"))?.count)
        assertNull(NotificationDotStore.dot(shortcut("other")))
    }

    @Test
    fun nonAdaptiveIconUsesSafeOriginalFallback() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val original = ColorDrawable(Color.RED)
        val app = LaunchableApp("pkg", "Main", 0, ProfileKind.PERSONAL, "App", "app", null, original)
        assertSame(original, ThemedIconRenderer(context).icon(app, true, 0))
    }

    private fun shortcut(id: String) = LauncherShortcut(
        ShortcutId("pkg", id, 0UL), id, "", null, android.os.Process.myUserHandle(), true, false, true, 0,
    )
}
