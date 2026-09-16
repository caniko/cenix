package com.caniko.cenix

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.caniko.cenix.uniffi.BackupProfileRef
import com.caniko.cenix.uniffi.CategoryAssignment
import com.caniko.cenix.uniffi.DrawerTaxonomy
import com.caniko.cenix.uniffi.IconOverride
import com.caniko.cenix.uniffi.ProfileKind
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DrawerBackupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val targets = listOf(BackupProfileRef(42u, ProfileKind.PERSONAL), BackupProfileRef(99u, ProfileKind.WORK))

    @Before fun clear() {
        context.getSharedPreferences("cenix_drawer", 0).edit().clear().commit()
        context.getSharedPreferences("cenix_icons", 0).edit().clear().commit()
    }

    private fun taxonomy(selected: String, id: String, title: String, order: List<String>) =
        DrawerTaxonomy(selected, listOf(com.caniko.cenix.uniffi.DrawerCategory(id, title)), order)

    @Test fun restoreUsesAlreadyRemappedSerialsAndMissingPackFallsBack() {
        val drawer = DrawerBackupExport.empty().copy(
            personal = taxonomy("games", "user_retro", "Retro", listOf("games", "user_retro")),
            work = taxonomy("productivity", "user_work", "Work", listOf("productivity", "user_work")),
            assignments = listOf(CategoryAssignment("app.personal", 42u, "games"), CategoryAssignment("app.work", 99u, "productivity")),
            iconPack = "missing.pack",
            iconOverrides = listOf(IconOverride("app.personal", "Main", 42u, "missing.pack", "icon")),
        )
        DrawerBackupExport.apply(context, drawer, targets)
        assertEquals(mapOf("app.personal|42" to "games", "app.work|99" to "productivity"), CategoryStore(context).overrides())
        assertEquals(listOf("user_retro"), CategoryStore(context).customCategories(42).map { it.id })
        assertEquals(listOf("user_work"), CategoryStore(context).customCategories(99).map { it.id })
        assertEquals("games", CategoryStore(context).selectedCategory(42))
        assertEquals("productivity", CategoryStore(context).selectedCategory(99))
        val packs = IconPackManager(context)
        assertEquals(42uL, packs.rawOverrides().single().profileId)
        assertEquals("missing.pack", packs.selectedPack())
        assertNull(packs.resolve(LaunchableApp("app.personal", "Main", 42, ProfileKind.PERSONAL, "App", "app", null, null)))
        val exported = DrawerBackupExport.build(context, targets, true)
        assertEquals(listOf(0uL, 1uL), exported.assignments.map { it.profileId })
        assertEquals(0uL, exported.iconOverrides.single().profileId)
        assertEquals("user_retro", exported.personal.categories.single().id)
        assertEquals("user_work", exported.work?.categories?.single()?.id)
        DrawerBackupExport.apply(context, drawer, targets) // Replay is idempotent.
        assertEquals(exported, DrawerBackupExport.build(context, targets, true))
    }

    @Test fun personalExportExcludesWorkTaxonomyAndLegacyRestoreClearsCustomization() {
        val store = CategoryStore(context)
        val personalId = store.createCustom("Personal", 42)
        val workId = store.createCustom("Work", 99)
        assertTrue(personalId.isNotEmpty() && workId.isNotEmpty())
        val prefs = context.getSharedPreferences("cenix_drawer", 0)
        prefs.edit().putString("assign|app.personal|42", "games")
            .putString("assign|app.work|99", "productivity")
            .putString("assign|app.private|123", "games").commit()
        val document = DrawerBackupExport.build(context, targets + BackupProfileRef(123u, ProfileKind.PRIVATE), false)
        assertEquals(listOf(CategoryAssignment("app.personal", 0u, "games")), document.assignments)
        assertEquals(listOf(personalId), document.personal.categories.map { it.id })
        assertNull(document.work)
        DrawerBackupExport.apply(context, DrawerBackupExport.empty(), targets)
        assertTrue(CategoryStore(context).overrides().isEmpty())
        assertTrue(CategoryStore(context).customCategories(42).isEmpty())
        assertTrue(CategoryStore(context).customCategories(99).isEmpty())
        assertNull(IconPackManager(context).selectedPack())
    }

    @Test fun legacyGlobalDefinitionsResetOnceWithoutTouchingProfileState() {
        val prefs = context.getSharedPreferences("cenix_drawer", 0)
        prefs.edit().putStringSet("custom_names", setOf("user_legacy"))
            .putString("custom_label|user_legacy", "Legacy")
            .putString("order", "games,user_legacy")
            .putString("selected", "user_legacy")
            .putString("assign|app.personal|42", "games")
            .putString("assign|app.orphan|42", "user_legacy").commit()
        val store = CategoryStore(context)
        assertTrue(store.clearLegacyDevState())
        assertFalse(prefs.contains("custom_names"))
        assertFalse(prefs.contains("order"))
        assertFalse(prefs.contains("selected"))
        // Per-profile rows and unrelated keys survive the reset.
        assertEquals("games", prefs.getString("assign|app.personal|42", null))
        // Assignments orphaned by the removed legacy definitions are cleared.
        assertFalse(prefs.contains("assign|app.orphan|42"))
        assertTrue(store.customCategories(42).isEmpty())
        assertFalse(store.clearLegacyDevState())
    }

    @Test fun legacyResetValidatesAssignmentsAgainstTheirOwnProfile() {
        val prefs = context.getSharedPreferences("cenix_drawer", 0)
        prefs.edit()
            .putStringSet("custom_names|42", setOf("user_personal"))
            .putString("custom_label|42|user_personal", "Personal")
            .putStringSet("custom_names|99", setOf("user_work"))
            .putString("custom_label|99|user_work", "Work")
            .putString("assign|app.own|42", "user_personal")
            .putString("assign|app.cross|42", "user_work")
            .putString("assign|app.gone|42", "user_deleted").commit()
        // Simulate a v1-stamped install so only the corrected v2 pass runs.
        prefs.edit().putBoolean("legacy_reset_v1", true).commit()
        val store = CategoryStore(context)
        assertTrue(store.clearLegacyDevState())
        assertEquals("user_personal", prefs.getString("assign|app.own|42", null))
        assertFalse(prefs.contains("assign|app.cross|42"))
        assertFalse(prefs.contains("assign|app.gone|42"))
        assertFalse(store.clearLegacyDevState())
    }

    @Test fun scopedAndFallbackAgree() {
        // In unit tests (no native lib) scoped() takes the Kotlin fallback; with the
        // host .so loaded it takes the Rust path. Either way both entries must agree.
        val drawer = DrawerBackupExport.empty().copy(
            personal = taxonomy("games", "user_retro", "Retro", listOf("games", "user_retro")),
            work = taxonomy("productivity", "user_work", "Work", listOf("productivity")),
            assignments = listOf(
                CategoryAssignment("app.personal", 42u, "games"),
                CategoryAssignment("app.work", 99u, "productivity"),
                CategoryAssignment("app.gone", 7u, "games"),
            ),
            iconOverrides = listOf(IconOverride("app.work", "Main", 99u, "pack", "icon")),
        )
        val personalOnly = listOf(BackupProfileRef(42u, ProfileKind.PERSONAL))
        assertEquals(
            DrawerBackupExport.fallbackScoped(drawer, targets),
            DrawerBackupExport.scoped(drawer, targets),
        )
        val scoped = DrawerBackupExport.scoped(drawer, personalOnly)
        assertEquals(scoped, DrawerBackupExport.fallbackScoped(drawer, personalOnly))
        assertNull(scoped.work)
        assertEquals(listOf(CategoryAssignment("app.personal", 42u, "games")), scoped.assignments)
        assertTrue(scoped.iconOverrides.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            DrawerBackupExport.fallbackScoped(drawer, emptyList())
        }
    }

    @Test fun restoreAppliesSavedDrawerMode() {
        val drawer = DrawerBackupExport.empty().copy(mode = "all")
        DrawerBackupExport.apply(context, drawer, targets)
        assertEquals("all", CategoryStore(context).drawerMode())
        val back = DrawerBackupExport.empty().copy(mode = "sections")
        DrawerBackupExport.apply(context, back, targets)
        assertEquals("sections", CategoryStore(context).drawerMode())
    }
}
