package com.caniko.cenix

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.*
import com.caniko.cenix.uniffi.ProfileKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DrawerNavigationTest {
    @Test fun emergencyControlsRemainTouchableAboveDrawer() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_Cenix)
        activity.setContentView(R.layout.activity_home)
        val root = activity.findViewById<LauncherRoot>(R.id.launcherRoot)
        root.surface = LauncherSurface.EMERGENCY
        val drawer = activity.findViewById<View>(R.id.allAppsContainer)
        drawer.visibility = View.VISIBLE
        val emergency = activity.findViewById<View>(R.id.emergencyOverlay)
        emergency.visibility = View.VISIBLE
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 320, 640)
        drawer.setPadding(16, 16 + emergency.height, 16, 16)
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 320, 640)
        val clicked = mutableListOf<Int>()
        for (id in listOf(R.id.retryNative, R.id.resetState)) {
            val button = activity.findViewById<View>(id)
            button.setOnClickListener { clicked.add(id) }
            val bounds = Rect()
            button.getDrawingRect(bounds)
            root.offsetDescendantRectToMyCoords(button, bounds)
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(0, 10, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                root.dispatchTouchEvent(event)
                event.recycle()
            }
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals("emergency recovery actions must receive touches above All Apps",
            listOf(R.id.retryNative, R.id.resetState), clicked)
        controller.pause().stop().destroy()
    }

    @Test fun drawerCannotCoverContextActions() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_Cenix)
        activity.setContentView(R.layout.activity_home)
        val root = activity.findViewById<LauncherRoot>(R.id.launcherRoot)
        root.surface = LauncherSurface.ALL_APPS
        activity.findViewById<View>(R.id.allAppsContainer).visibility = View.VISIBLE
        var opened = false
        val popup = ContextPopup(activity).apply {
            bind("Cenix Fixture", emptyList(), true, false, true)
            onAppInfo = { opened = true }
        }
        activity.findViewById<DragLayer>(R.id.dragLayer).addView(popup,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 320, 640)
        val info = popup.findViewById<View>(R.id.context_app_info)
        val bounds = Rect()
        info.getDrawingRect(bounds)
        root.offsetDescendantRectToMyCoords(info, bounds)
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0, 10, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            root.dispatchTouchEvent(event)
            event.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("context actions must receive touches above All Apps", opened)
        controller.pause().stop().destroy()
    }

    @Test fun shortDrawerKeepsPersonalResultsAndPrivateControlsReachable() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        activity.setTheme(R.style.Theme_Cenix)
        activity.setContentView(R.layout.activity_home)
        val root = activity.findViewById<LauncherRoot>(R.id.launcherRoot)
        val drawer = activity.findViewById<View>(R.id.allAppsContainer)
        drawer.visibility = View.VISIBLE
        activity.findViewById<View>(R.id.workTab).visibility = View.VISIBLE
        activity.findViewById<View>(R.id.privateSpaceContainer).visibility = View.VISIBLE
        val personal = activity.findViewById<GridView>(R.id.appList)
        val private = activity.findViewById<GridView>(R.id.privateAppList)
        private.visibility = View.VISIBLE
        for (grid in listOf(personal, private)) {
            grid.adapter = ArrayAdapter(activity, R.layout.app_grid_cell, R.id.appLabel, listOf("Cenix Fixture"))
        }
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(322, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 320, 322)
        val result = personal.getChildAt(0)
        assertNotNull("private-space chrome must not collapse personal results", result)
        val visible = Rect()
        assertTrue(result.getGlobalVisibleRect(visible))
        assertEquals("a complete result must fit above the keyboard", result.height, visible.height())
        val scroll = drawer as ScrollView
        assertTrue(scroll.canScrollVertically(1))
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        val settings = activity.findViewById<View>(R.id.launcherSettings)
        assertTrue(settings.getGlobalVisibleRect(visible))
        assertEquals(settings.height, visible.height())
        assertTrue(private.getGlobalVisibleRect(visible))
        root.surface = LauncherSurface.ALL_APPS
        root.onSurfaceRequested = { root.surface = it }
        fun touch(action: Int, y: Float) {
            val event = MotionEvent.obtain(0, 10, action, 200f, y, 0)
            root.dispatchTouchEvent(event)
            event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, 200f)
        touch(MotionEvent.ACTION_MOVE, 280f)
        scroll.scrollTo(0, 0)
        touch(MotionEvent.ACTION_UP, 280f)
        assertEquals("scrolling back through results must not dismiss the drawer", LauncherSurface.ALL_APPS, root.surface)
        touch(MotionEvent.ACTION_DOWN, 200f)
        touch(MotionEvent.ACTION_MOVE, 280f)
        touch(MotionEvent.ACTION_UP, 280f)
        assertEquals("a new swipe at the top still dismisses the drawer", LauncherSurface.HOME, root.surface)
        controller.pause().stop().destroy()
    }

    @Test fun focusEntersVisibleSectionsAndDoesNotEatEmptyResults() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val all = GridView(activity).apply { visibility = View.GONE }
        val private = GridView(activity).apply { visibility = View.GONE }
        activity.setContentView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; addView(all); addView(private)
        })
        assertFalse(DrawerNavigation.focusFirstResult(all, private))
        all.visibility = View.VISIBLE
        all.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, listOf("App"))
        assertTrue(DrawerNavigation.focusFirstResult(all, private))
        assertTrue(all.hasFocus())
        controller.pause().stop().destroy()
    }

    @Test fun rebuildKeepsFocusOnSameTagAndNeverStealsIt() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(container)
        activity.setContentView(root)
        fun row(tag: String) = Button(activity).apply { this.tag = tag; text = tag }
        container.addView(row("a"))
        container.addView(row("b"))
        container.getChildAt(1).requestFocus()
        DrawerNavigation.preserveFocus(container) {
            container.removeAllViews()
            container.addView(row("a"))
            container.addView(row("b"))
        }
        assertEquals("b", container.findFocus()?.tag)
        // Focus outside the rebuilt container is left alone.
        val outsider = Button(activity).apply { tag = "outside"; text = "outside" }
        root.addView(outsider)
        outsider.requestFocus()
        DrawerNavigation.preserveFocus(container) {
            container.removeAllViews()
            container.addView(row("a"))
        }
        assertEquals("outside", root.findFocus()?.tag)
        controller.pause().stop().destroy()
    }

    @Test fun sectionGroupingExcludesPrivateAndPreservesUnknownApps() {
        val app = LaunchableApp("app", "Main", 0, ProfileKind.PERSONAL, "App", "app", null, null, autoCategory = "games")
        val categories = listOf(DrawerCategory("games", null, 0, true), DrawerCategory("uncategorized", null, 1, true))
        val groups = AppCategories.sections(listOf(app, app.copy(packageName = "unknown", autoCategory = "deleted"),
            app.copy(packageName = "private", profileKind = ProfileKind.PRIVATE)), categories, emptyMap())
        assertEquals(listOf("games", "uncategorized"), groups.map { it.first.id })
        assertEquals(listOf("app", "unknown"), groups.flatMap { it.second }.map { it.packageName })
    }
}
