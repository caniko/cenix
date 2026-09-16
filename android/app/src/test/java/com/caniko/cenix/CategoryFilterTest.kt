package com.caniko.cenix

import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.caniko.cenix.uniffi.ProfileKind

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CategoryFilterTest {
    private val user = android.os.Process.myUserHandle()

    private fun app(pkg: String, auto: String, profile: Long = 0) = LaunchableApp(
        packageName = pkg,
        className = "C",
        profileId = profile,
        profileKind = ProfileKind.PERSONAL,
        label = pkg,
        normalizedLabel = pkg,
        user = user,
        icon = null,
        autoCategory = auto,
    )

    @Test
    fun platformHintsMapToDrawerCategories() {
        assertEquals(AppCategories.GAMES, AppCategories.fromPlatform(ApplicationInfo.CATEGORY_GAME))
        assertEquals(AppCategories.PRODUCTIVITY, AppCategories.fromPlatform(ApplicationInfo.CATEGORY_PRODUCTIVITY))
        assertEquals(AppCategories.SOCIAL, AppCategories.fromPlatform(ApplicationInfo.CATEGORY_SOCIAL))
        assertEquals(AppCategories.UNCATEGORIZED, AppCategories.fromPlatform(ApplicationInfo.CATEGORY_UNDEFINED))
    }

    @Test
    fun manualOverrideWinsAndResetsToAutomatic() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = CategoryStore(ctx)
        val game = app("game.pkg", AppCategories.GAMES)
        assertEquals(AppCategories.GAMES, store.effective(game))
        store.setOverride(game, AppCategories.PRODUCTIVITY)
        assertEquals(AppCategories.PRODUCTIVITY, store.effective(game))
        store.setOverride(game, null)
        assertEquals(AppCategories.GAMES, store.effective(game))
    }

    @Test
    fun deletingCustomCategoryFallsBackToAutomatic() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = CategoryStore(ctx)
        val id = store.createCustom("Retro", 0)
        val game = app("retro.pkg", AppCategories.GAMES)
        store.setOverride(game, id)
        assertEquals(id, store.effective(game))
        store.deleteCustom(id, 0)
        assertEquals(AppCategories.GAMES, store.effective(game))
    }

    @Test
    fun uncertainProfilesKeepRowsUntilClassified() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = ctx.getSharedPreferences("cenix_drawer", 0)
        prefs.edit().putString("assign|app.unknown|77", "games")
            .putStringSet("custom_names|77", setOf("user_x"))
            .putString("custom_label|77|user_x", "X")
            .putString("order|77", "games,user_x")
            .putString("selected|77", "user_x").commit()
        val store = CategoryStore(ctx)
        // First discovery with no confirmed profiles: uncertain serials are preserved.
        store.retainProfiles(emptySet(), setOf(77))
        assertEquals("games", prefs.getString("assign|app.unknown|77", null))
        assertEquals(listOf("user_x"), store.customCategories(77).map { it.id })
        assertEquals("user_x", store.selectedCategory(77))
        // Confirmed removal deletes the same rows.
        store.retainProfiles(emptySet())
        assertTrue(store.overrides().isEmpty())
        assertTrue(store.customCategories(77).isEmpty())
        assertEquals(AppCategories.ALL, store.selectedCategory(77))
    }

    @Test
    fun coldStartPreservesLocallyKnownSerials() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = ctx.getSharedPreferences("cenix_drawer", 0)
        prefs.edit().putString("assign|app.unknown|77", "games")
            .putStringSet("custom_names|77", setOf("user_x"))
            .putString("custom_label|77|user_x", "X").commit()
        val store = CategoryStore(ctx)
        assertEquals(setOf(77L), store.knownSerials())
        // Cold start before any in-memory discovery: locally known serials are
        // preserved even with no confirmed or uncertain profiles.
        store.retainProfiles(emptySet(), store.knownSerials())
        assertEquals("games", prefs.getString("assign|app.unknown|77", null))
        assertEquals(listOf("user_x"), store.customCategories(77).map { it.id })
    }

    @Test
    fun collidingNamesGetDistinctStableIdsAndScopesStaySeparate() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = CategoryStore(ctx)
        val first = store.createCustom("A+B", 0)
        val second = store.createCustom("A B", 0)
        assertTrue(first.isNotEmpty() && second.isNotEmpty() && first != second)
        val work = store.createCustom("A+B", 99)
        assertTrue(work.isNotEmpty() && work != first)
        assertTrue(store.customCategories(0).map { it.id }.containsAll(listOf(first, second)))
        assertEquals(listOf(work), store.customCategories(99).map { it.id })
        store.setSelected(0, first)
        assertEquals(AppCategories.ALL, store.selectedCategory(99))
    }
}
