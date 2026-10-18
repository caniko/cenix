package com.caniko.cenix

import android.content.pm.LauncherApps
import android.content.pm.PackageInstaller
import android.os.Looper
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLauncherApps
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CenixApplication::class, shadows = [HomeReloadTest.NoInstallerSessions::class])
@LooperMode(LooperMode.Mode.PAUSED)
class HomeReloadTest {
    // Robolectric 4.14 does not implement these unrelated installer APIs.
    @Implements(LauncherApps::class)
    class NoInstallerSessions : ShadowLauncherApps() {
        @Implementation override fun registerPackageInstallerSessionCallback(executor: Executor, callback: PackageInstaller.SessionCallback) = Unit
        @Implementation override fun unregisterPackageInstallerSessionCallback(callback: PackageInstaller.SessionCallback) = Unit
        @Implementation override fun getAllPackageInstallerSessions(): List<PackageInstaller.SessionInfo> = emptyList()
    }

    @Test fun supersededPackageReloadStillClosesContextActions() {
        val app = ApplicationProvider.getApplicationContext<CenixApplication>()
        assertTrue(app.awaitReady())
        app.emergency = true
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            CenixExecutors.io.submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val popup = ContextPopup(activity)
            activity.findViewById<DragLayer>(R.id.dragLayer).addView(popup,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            ReflectionHelpers.setField(activity, "contextPopup", popup)
            val reload = HomeActivity::class.java.getDeclaredMethod(
                "scheduleReload", String::class.java, PackageKey::class.java,
            ).apply { isAccessible = true }
            reload.invoke(activity, "remove", PackageKey("com.caniko.cenix.fixture", 0))
            CenixExecutors.io.submit {}.get(10, TimeUnit.SECONDS)
            // The removal's cleanup is queued, but another reload owns publication.
            reload.invoke(activity, "start", null)
            CenixExecutors.io.submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("a newer catalog must not discard package-removal cleanup", popup.parent)
            assertNull(ReflectionHelpers.getField<ContextPopup?>(activity, "contextPopup"))

            activity.findViewById<DragLayer>(R.id.dragLayer).addView(popup,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            ReflectionHelpers.setField(activity, "contextPopup", popup)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blocker = CenixExecutors.io.submit {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            try {
                assertTrue("the serialized IO queue must be blocked", entered.await(10, TimeUnit.SECONDS))
                reload.invoke(activity, "remove", PackageKey("com.caniko.cenix.fixture", 0))
                reload.invoke(activity, "start", null)
            } finally {
                release.countDown()
            }
            blocker.get(10, TimeUnit.SECONDS)
            CenixExecutors.io.submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("superseding before IO must retain package-removal cleanup", popup.parent)
            assertNull(ReflectionHelpers.getField<ContextPopup?>(activity, "contextPopup"))
        } finally {
            controller.pause().stop().destroy()
            CenixExecutors.io.submit {}.get(10, TimeUnit.SECONDS)
            NotificationDotStore.setEnabled(true)
            NotificationDotStore.replace(emptyMap())
            NotificationDotStore.setRefresher(null)
        }
    }
}
