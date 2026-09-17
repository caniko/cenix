package com.caniko.cenix

import android.app.Activity
import android.app.Application
import android.view.View
import android.widget.*
import com.caniko.cenix.uniffi.ProfileKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DrawerNavigationTest {
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
