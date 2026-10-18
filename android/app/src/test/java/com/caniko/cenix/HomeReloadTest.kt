package com.caniko.cenix

import android.content.pm.LauncherApps
import android.content.pm.PackageInstaller
import android.os.Looper
import android.os.Process
import android.os.UserManager
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.caniko.cenix.db.ApplicationItemEntity
import com.caniko.cenix.db.WorkspaceItemEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowUserManager.UserState
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

    @Test fun supersededRemovalKeepsReinstalledPlacementButGenericReloadRetainsRemoval() {
        val app = ApplicationProvider.getApplicationContext<CenixApplication>()
        assertTrue(app.awaitReady())
        val user = Process.myUserHandle()
        shadowOf(app.getSystemService(UserManager::class.java)).apply {
            setSerialNumberForUser(user, 0)
            setUserState(user, UserState.STATE_RUNNING_UNLOCKED)
            setUserUnlocked(true)
        }
        app.emergency = false
        app.filterFactory = { EmergencyAppFilter }
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup().visible()
        val activity = controller.get()
        val key = PackageKey("com.caniko.cenix.fixture", 0)
        try {
            CenixExecutors.io.submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val database = checkNotNull(app.database)
            val placed = WorkspaceItemEntity(1, "HOTSEAT", 0, 2, 0, 1, 1, WorkspaceItemEntity.ITEM_APPLICATION)
            CenixExecutors.io.submit {
                val state = database.dao().workspaceState()
                database.dao().commitWorkspace(
                    state.metadata.generation,
                    state.metadata.copy(generation = state.metadata.generation + 1, nextItemId = 2),
                    state.pages,
                    listOf(placed),
                    listOf(ApplicationItemEntity(1, key.packageName, "MainActivity", key.profileId)),
                )
            }.get(10, TimeUnit.SECONDS)
            assertFalse("workspace reconciliation must use the real native engine", app.emergency)
            val reload = HomeActivity::class.java.getDeclaredMethod(
                "scheduleReload", String::class.java, PackageKey::class.java,
            ).apply { isAccessible = true }
            for (winner in listOf("add", "start")) {
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val blocker = CenixExecutors.io.submit {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                try {
                    assertTrue("the serialized IO queue must be blocked", entered.await(10, TimeUnit.SECONDS))
                    reload.invoke(activity, "remove", key)
                    reload.invoke(activity, winner, if (winner == "add") key else null)
                } finally {
                    release.countDown()
                }
                blocker.get(10, TimeUnit.SECONDS)
                CenixExecutors.io.submit {}.get(10, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse("reload must not hide a native failure", app.emergency)
                val remaining = CenixExecutors.io.submit<List<WorkspaceItemEntity>> {
                    database.dao().workspaceItems()
                }.get(10, TimeUnit.SECONDS)
                if (winner == "add") {
                    assertEquals("a superseded removal must preserve the reinstalled app's exact placement", listOf(placed), remaining)
                } else {
                    assertTrue("a generic winner must still apply a genuine package removal", remaining.isEmpty())
                }
            }
        } finally {
            controller.pause().stop().destroy()
            CenixExecutors.io.submit {}.get(10, TimeUnit.SECONDS)
            NotificationDotStore.setEnabled(true)
            NotificationDotStore.replace(emptyMap())
            NotificationDotStore.setRefresher(null)
        }
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
