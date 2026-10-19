package com.caniko.cenix

import android.content.pm.LauncherApps
import android.content.pm.PackageInstaller
import android.graphics.Rect
import android.os.Looper
import android.os.Process
import android.os.UserManager
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ArrayAdapter
import androidx.test.core.app.ApplicationProvider
import com.caniko.cenix.db.ApplicationItemEntity
import com.caniko.cenix.db.WorkspaceItemEntity
import com.caniko.cenix.uniffi.WorkspaceSnapshot
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
import org.robolectric.shadows.ShadowLog
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
            assertFalse("workspace reconciliation must use the real native engine", app.emergency)
            val reload = HomeActivity::class.java.getDeclaredMethod(
                "scheduleReload", String::class.java, PackageKey::class.java,
            ).apply { isAccessible = true }
            for (window in listOf("queued", "mutation", "authority")) {
                val duringMutation = window != "queued"
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
                for (winner in if (window == "authority") listOf("add") else listOf("add", "start")) {
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    val originalLog = ShadowLog.stream
                    val armed = AtomicBoolean(true)
                    if (duringMutation) ShadowLog.stream = object : PrintStream(ByteArrayOutputStream()) {
                        override fun println(line: String?) {
                            if (line?.contains("WORKSPACE_COMMAND category=drop-missing") == true && armed.compareAndSet(true, false)) {
                                entered.countDown()
                                check(release.await(10, TimeUnit.SECONDS))
                            }
                        }
                    }
                    val blocker = if (duringMutation) null else CenixExecutors.io.submit {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    val removals = ReflectionHelpers.getField<MutableSet<PackageKey>>(activity, "removedPackages")
                    if (window == "authority") {
                        val map = object : java.util.concurrent.ConcurrentHashMap<PackageKey, Boolean>() {
                            override fun remove(key: PackageKey): Boolean? = super.remove(key).also {
                                removals.remove(key)
                                // Finish the old transaction while the reinstall's authority update is in flight.
                                release.countDown()
                                CenixExecutors.io.submit {}.get(10, TimeUnit.SECONDS)
                            }
                        }
                        removals.forEach { map[it] = true }
                        ReflectionHelpers.setField(activity, "removedPackages", map.keySet(true))
                    }
                    try {
                        if (!duringMutation) assertTrue("the serialized IO queue must be blocked", entered.await(10, TimeUnit.SECONDS))
                        reload.invoke(activity, "remove", key)
                        if (duringMutation) assertTrue("the native mutation must pause after the entry guard", entered.await(10, TimeUnit.SECONDS))
                        reload.invoke(activity, winner, if (winner == "add") key else null)
                    } finally {
                        release.countDown()
                        ShadowLog.stream = originalLog
                        ReflectionHelpers.setField(activity, "removedPackages", removals)
                    }
                    blocker?.get(10, TimeUnit.SECONDS)
                    CenixExecutors.io.submit {}.get(10, TimeUnit.SECONDS)
                    shadowOf(Looper.getMainLooper()).idle()
                    assertFalse("reload must not hide a native failure", app.emergency)
                    val remaining = CenixExecutors.io.submit<List<WorkspaceItemEntity>> {
                        database.dao().workspaceItems()
                    }.get(10, TimeUnit.SECONDS)
                    if (winner == "add") {
                        assertEquals("a superseded removal must preserve the reinstalled app's exact placement (window=$window)", listOf(placed), remaining)
                    } else {
                        assertTrue("a generic winner must still apply a genuine package removal", remaining.isEmpty())
                    }
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

    @Test fun dropWaitsForQueuedEdgePageAndItsLayout() {
        val app = ApplicationProvider.getApplicationContext<CenixApplication>()
        assertTrue(app.awaitReady())
        shadowOf(app.getSystemService(UserManager::class.java)).apply {
            setSerialNumberForUser(Process.myUserHandle(), 0)
            setUserState(Process.myUserHandle(), UserState.STATE_RUNNING_UNLOCKED)
            setUserUnlocked(true)
        }
        app.emergency = false
        app.filterFactory = { EmergencyAppFilter }
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            CenixExecutors.io.submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            val database = checkNotNull(app.database)
            val seeded = CenixExecutors.io.submit<WorkspaceSnapshot> {
                val state = database.dao().workspaceState()
                database.dao().commitWorkspace(
                    state.metadata.generation,
                    state.metadata.copy(generation = state.metadata.generation + 1, nextItemId = 2),
                    state.pages.take(1),
                    listOf(WorkspaceItemEntity(1, "WORKSPACE", state.pages.first().pageId, 0, 0)),
                    listOf(ApplicationItemEntity(1, "com.caniko.cenix.fixture", "MainActivity", 0)),
                )
                LauncherRepository(database).snapshot()
            }.get(10, TimeUnit.SECONDS)
            val render = HomeActivity::class.java.getDeclaredMethod("render", WorkspaceSnapshot::class.java)
                .apply { isAccessible = true }
            render.invoke(activity, seeded)
            val root = activity.findViewById<LauncherRoot>(R.id.launcherRoot)
            fun layout() {
                root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 320, 640)
            }
            layout()
            val pager = activity.findViewById<WorkspacePager>(R.id.workspaceGrid)
            val dragLayer = activity.findViewById<DragLayer>(R.id.dragLayer)
            assertTrue(dragLayer.beginDrag(checkNotNull(pager.currentLayout?.viewAt(0, 0)), LauncherDrag(null, 1UL)))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blocker = CenixExecutors.io.submit {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                // An earlier mutation can have a main-thread render queued when the edge commits.
                activity.runOnUiThread { render.invoke(activity, seeded) }
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                pager.edgeHover(pager.width - 1f)
                assertEquals("the new page is still queued", 1, pager.childCount)
                val origin = IntArray(2)
                pager.getLocationOnScreen(origin)
                val event = MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP,
                    origin[0] + pager.width * 0.75f, origin[1] + pager.height * 0.5f, 0)
                dragLayer.handleMotionEvent(event)
                event.recycle()
            } finally {
                release.countDown()
            }
            blocker.get(10, TimeUnit.SECONDS)
            repeat(3) {
                CenixExecutors.io.submit {}.get(10, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
                layout()
            }
            val state = CenixExecutors.io.submit<WorkspaceSnapshot> { LauncherRepository(database).snapshot() }
                .get(10, TimeUnit.SECONDS)
            assertFalse(app.emergency)
            assertEquals("the drop must occupy the new page instead of pruning it", 2, state.pages.size)
            assertEquals(com.caniko.cenix.uniffi.ContainerRef.Workspace(state.pages.last().pageId), state.items.single().container)
            assertEquals(1, pager.currentPage)
            assertFalse(dragLayer.isDragging)
            assertTrue(dragLayer.beginDrag(checkNotNull(pager.currentLayout?.getChildAt(0)), LauncherDrag(null, 1UL)))
            val event = MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP, 100f, 200f, 0)
            dragLayer.handleMotionEvent(event)
            event.recycle()
            dragLayer.cancel("profile-or-package")
            CenixExecutors.io.submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("cleanup must cancel a deferred drop", state, CenixExecutors.io.submit<WorkspaceSnapshot> {
                LauncherRepository(database).snapshot()
            }.get(10, TimeUnit.SECONDS))
        } finally {
            controller.pause().stop().destroy()
            CenixExecutors.io.submit {}.get(10, TimeUnit.SECONDS)
            NotificationDotStore.setEnabled(true)
            NotificationDotStore.replace(emptyMap())
            NotificationDotStore.setRefresher(null)
        }
    }

    @Test fun drawerHeaderDismissesWhileThePersonalGridRemainsScrolled() {
        val app = ApplicationProvider.getApplicationContext<CenixApplication>()
        assertTrue(app.awaitReady())
        app.emergency = false
        app.filterFactory = { EmergencyAppFilter }
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            CenixExecutors.io.submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            app.emergency = false
            val root = activity.findViewById<LauncherRoot>(R.id.launcherRoot)
            val drawer = activity.findViewById<AllAppsContainer>(R.id.allAppsContainer)
            val personal = activity.findViewById<AllAppsView>(R.id.appList)
            root.surface = LauncherSurface.ALL_APPS
            drawer.visibility = View.VISIBLE
            personal.adapter = ArrayAdapter(activity, R.layout.app_grid_cell, R.id.appLabel, (1..60).map { "App $it" })
            fun layout() {
                root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 320, 640)
            }
            layout()
            personal.setSelection(30)
            layout()
            assertTrue("the unrelated personal grid must stay scrolled", personal.canScrollVertically(-1))
            assertFalse("the surrounding drawer is at its top", drawer.canScrollVertically(-1))
            val header = activity.findViewById<View>(R.id.searchField)
            val bounds = Rect()
            header.getDrawingRect(bounds)
            root.offsetDescendantRectToMyCoords(header, bounds)
            for (step in 0..2) {
                val action = listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)[step]
                val event = MotionEvent.obtain(0, step * 100L, action, bounds.exactCenterX(), bounds.exactCenterY() + step * 100f, 0)
                root.dispatchTouchEvent(event)
                event.recycle()
            }
            assertEquals("header dismissal must not depend on another grid's scroll", LauncherSurface.HOME, root.surface)
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
