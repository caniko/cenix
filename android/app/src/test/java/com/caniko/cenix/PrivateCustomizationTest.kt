package com.caniko.cenix

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.caniko.cenix.uniffi.ProfileKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PrivateCustomizationTest {
    @Test fun privateAppIgnoresOldOverridesAndCannotPersistNewOnes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = LaunchableApp("private.app", "Main", 77, ProfileKind.PRIVATE, "App", "app", null, null)
        val categories = CategoryStore(context)
        context.getSharedPreferences("cenix_drawer", 0).edit().putString("assign|private.app|77", "games").commit()
        assertEquals(AppCategories.UNCATEGORIZED, categories.effective(app))
        categories.retainProfiles(setOf(0))
        categories.setOverride(app, "productivity")
        assertTrue(categories.overrides().isEmpty())
        val prefs = context.getSharedPreferences("cenix_icons", 0)
        prefs.edit().putString("override|private.app|Main|77", "pack|icon").commit()
        val packs = IconPackManager(context)
        assertNull(packs.overrideFor(app))
        packs.retainProfiles(setOf(0))
        assertTrue(prefs.all.isEmpty())
        prefs.edit().clear().commit()
        packs.setOverride(app, PackIconRef("pack", "icon"))
        assertTrue(prefs.all.isEmpty())
    }
}
