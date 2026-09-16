package com.caniko.cenix

import android.view.View
import android.widget.GridView

internal object DrawerNavigation {
    /**
     * Rebuilds a container's children without dropping keyboard/TalkBack focus:
     * when the focused view is destroyed by the rebuild, focus moves to the rebuilt
     * view carrying the same tag. Focus is never stolen from elsewhere.
     */
    fun <T> preserveFocus(container: android.view.ViewGroup, render: () -> T): T {
        val focusedTag = container.findFocus()?.tag
        val result = render()
        if (focusedTag != null) {
            val current = container.rootView.findFocus()
            if (current == null || current === container || !current.isShown) {
                container.findViewWithTag<View>(focusedTag)?.requestFocus()
            }
        }
        return result
    }

    fun focusFirstResult(sections: View, all: GridView, private: GridView): Boolean {
        if (sections.isShown) {
            sections.getFocusables(View.FOCUS_FORWARD).firstOrNull { it.isShown && it.isEnabled && it !== sections }
                ?.let { if (it.requestFocus()) return true }
        }
        for (grid in listOf(all, private)) {
            if (grid.isShown && grid.count > 0 && grid.requestFocus()) {
                grid.setSelection(0)
                return true
            }
        }
        return false
    }
}
