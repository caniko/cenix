package com.caniko.cenix

import android.content.Context
import android.content.pm.ApplicationInfo

// ponytail: single-category drawer model; multi-tag taxonomy only if users demand it.
object AppCategories {
    internal fun sections(
        apps: List<LaunchableApp>, ordered: List<DrawerCategory>, overrides: Map<String, String>,
    ): List<Pair<DrawerCategory, List<LaunchableApp>>> {
        val known = ordered.map { it.id }
        val knownSet = known.toHashSet()
        val groups = apps.filter { it.profileKind != com.caniko.cenix.uniffi.ProfileKind.PRIVATE }.groupBy { app ->
            val requested = assignment(app, overrides)
            try {
                com.caniko.cenix.uniffi.drawerSectionKey(requested, known)
            } catch (_: Throwable) {
                requested.takeIf { it in knownSet } ?: UNCATEGORIZED
            }
        }
        val collator = java.text.Collator.getInstance()
        return ordered.mapNotNull { category ->
            groups[category.id]?.let { category to it.sortedWith { a, b -> collator.compare(a.label, b.label) } }
        }
    }

    const val ALL = "all"
    const val UNCATEGORIZED = "uncategorized"
    const val GAMES = "games"
    const val AUDIO = "audio"
    const val VIDEO = "video"
    const val IMAGE = "image"
    const val SOCIAL = "social"
    const val NEWS = "news"
    const val MAPS = "maps"
    const val PRODUCTIVITY = "productivity"
    const val ACCESSIBILITY = "accessibility"

    // Canonical order and membership live in Rust (cenix-core drawer module);
    // the local list is the omitNative/emergency fallback.
    private val FALLBACK_BUILTINS = listOf(GAMES, PRODUCTIVITY, SOCIAL, AUDIO, VIDEO, IMAGE, NEWS, MAPS, ACCESSIBILITY, UNCATEGORIZED)
    val BUILTINS: List<String>
        get() = try {
            com.caniko.cenix.uniffi.drawerBuiltinCategories()
        } catch (_: Throwable) {
            FALLBACK_BUILTINS
        }

    fun fromPlatform(category: Int): String = when (category) {
        ApplicationInfo.CATEGORY_GAME -> GAMES
        ApplicationInfo.CATEGORY_AUDIO -> AUDIO
        ApplicationInfo.CATEGORY_VIDEO -> VIDEO
        ApplicationInfo.CATEGORY_IMAGE -> IMAGE
        ApplicationInfo.CATEGORY_SOCIAL -> SOCIAL
        ApplicationInfo.CATEGORY_NEWS -> NEWS
        ApplicationInfo.CATEGORY_MAPS -> MAPS
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> PRODUCTIVITY
        ApplicationInfo.CATEGORY_ACCESSIBILITY -> ACCESSIBILITY
        else -> UNCATEGORIZED
    }

    fun validCustomId(id: String): Boolean = try {
        com.caniko.cenix.uniffi.drawerIsValidCustomId(id)
    } catch (_: Throwable) {
        id.isNotEmpty() && id.length <= 64 && id != ALL &&
            id !in FALLBACK_BUILTINS && id.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }
    }

    internal fun assignment(app: LaunchableApp, overrides: Map<String, String>): String {
        val override = overrides["${app.packageName}|${app.profileId}"]
        return try {
            com.caniko.cenix.uniffi.drawerAssignmentCategory(app.autoCategory, override, app.supportsCustomization)
        } catch (_: Throwable) {
            if (app.supportsCustomization) override ?: app.autoCategory else app.autoCategory
        }
    }

    internal fun sanitizeTitle(raw: String): String? = try {
        com.caniko.cenix.uniffi.drawerSanitizeTitle(raw)
    } catch (_: Throwable) {
        raw.trim().take(40).takeIf { it.isNotEmpty() && it.none { c -> c.isISOControl() } }
    }

    internal fun orderedIds(customIds: List<String>, order: List<String>): List<String> = try {
        com.caniko.cenix.uniffi.drawerOrderCategories(customIds, order)
    } catch (_: Throwable) {
        val customs = customIds.filter { validCustomId(it) }.sorted()
        val default = FALLBACK_BUILTINS + customs
        val rank = order.filter { it in default }.withIndex().associate { it.value to it.index }
        default.sortedBy { rank[it] ?: Int.MAX_VALUE }
    }

    fun label(context: Context, id: String, customName: String? = null): String {
        if (id == ALL) return context.getString(R.string.category_all)
        if (customName != null) return customName
        return when (id) {
            GAMES -> context.getString(R.string.category_games)
            PRODUCTIVITY -> context.getString(R.string.category_productivity)
            SOCIAL -> context.getString(R.string.category_social)
            AUDIO -> context.getString(R.string.category_audio)
            VIDEO -> context.getString(R.string.category_video)
            IMAGE -> context.getString(R.string.category_image)
            NEWS -> context.getString(R.string.category_news)
            MAPS -> context.getString(R.string.category_maps)
            ACCESSIBILITY -> context.getString(R.string.category_accessibility)
            UNCATEGORIZED -> context.getString(R.string.category_uncategorized)
            else -> id
        }
    }
}

data class DrawerCategory(val id: String, val name: String?, val rank: Int, val builtin: Boolean)

class CategoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("cenix_drawer", Context.MODE_PRIVATE)

    companion object {
        // Soft live-edit guards; authoritative limits live in Rust (drawer
        // module) and are enforced on backup import.
        const val MAX_CUSTOM_CATEGORIES = 64
        const val MAX_ORDER_ENTRIES = 128
        const val MAX_TITLE_CHARS = 40
    }

    fun effective(app: LaunchableApp, custom: Map<String, String> = overrides()): String =
        // ponytail: package-level override wins; component split only if a pack proves it needed.
        AppCategories.assignment(app, custom)

    fun overrides(): Map<String, String> {
        return prefs.all.mapNotNull { (k, v) ->
            if (k.startsWith("assign|") && v is String) k.removePrefix("assign|") to v else null
        }.toMap()
    }

    fun setOverride(app: LaunchableApp, categoryId: String?) {
        if (!app.supportsCustomization) return
        val key = "assign|${app.packageName}|${app.profileId}"
        prefs.edit().apply { if (categoryId == null) remove(key) else putString(key, categoryId) }.apply()
    }

    // Custom definitions, order and selection are scoped per profile serial so a
    // personal-only backup never carries work taxonomy. The drawer mode stays global.
    private fun namesKey(serial: Long) = "custom_names|$serial"
    private fun labelKey(serial: Long, id: String) = "custom_label|$serial|$id"
    private fun orderKey(serial: Long) = "order|$serial"
    private fun selectedKey(serial: Long) = "selected|$serial"

    fun customCategories(serial: Long): List<DrawerCategory> {
        val names = prefs.getStringSet(namesKey(serial), emptySet()).orEmpty()
        return names.sorted().mapIndexed { i, id ->
            DrawerCategory(id, prefs.getString(labelKey(serial, id), id), 100 + i, false)
        }
    }

    fun orderedCategories(serial: Long): List<DrawerCategory> {
        val order = prefs.getString(orderKey(serial), null)?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        val customs = customCategories(serial).associateBy { it.id }
        val builtins = AppCategories.BUILTINS.mapIndexed { i, id -> DrawerCategory(id, null, i, true) }
        val byId = builtins.associateBy { it.id } + customs
        // Ordering is canonical in Rust; stored ranks only describe the result.
        return AppCategories.orderedIds(customs.keys.sorted(), order).mapNotNull { byId[it] }
    }

    fun createCustom(name: String, serial: Long): String {
        val clean = AppCategories.sanitizeTitle(name) ?: return ""
        val existing = prefs.getStringSet(namesKey(serial), emptySet()).orEmpty()
        if (existing.size >= MAX_CUSTOM_CATEGORIES) return ""
        // Stable random IDs: distinct names never collide and IDs survive renames.
        var id = ""
        repeat(8) {
            val candidate = "user_" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)
            if (candidate !in existing) {
                id = candidate
                return@repeat
            }
        }
        if (id.isEmpty() || id in existing) return ""
        val names = existing.toMutableSet()
        names.add(id)
        prefs.edit().putStringSet(namesKey(serial), names).putString(labelKey(serial, id), clean).apply()
        return id
    }

    fun renameCustom(id: String, serial: Long, name: String) {
        val clean = AppCategories.sanitizeTitle(name) ?: return
        if (id !in prefs.getStringSet(namesKey(serial), emptySet()).orEmpty()) return
        prefs.edit().putString(labelKey(serial, id), clean).apply()
    }

    fun deleteCustom(id: String, serial: Long) {
        val names = prefs.getStringSet(namesKey(serial), emptySet()).orEmpty().toMutableSet()
        if (!names.remove(id)) return
        val edit = prefs.edit().putStringSet(namesKey(serial), names).remove(labelKey(serial, id))
        // Move this profile's apps in the deleted category back to automatic.
        prefs.all.keys.filter { it.startsWith("assign|") && it.endsWith("|$serial") }.forEach { k ->
            if (prefs.getString(k, null) == id) edit.remove(k)
        }
        edit.apply()
    }

    fun reorder(ids: List<String>, serial: Long) {
        prefs.edit().putString(orderKey(serial), ids.take(MAX_ORDER_ENTRIES).joinToString(",").take(2000)).apply()
    }

    internal fun replace(
        mode: String,
        scopes: Map<Long, com.caniko.cenix.uniffi.DrawerTaxonomy>,
        assignments: List<Triple<String, Long, String>>,
    ) {
        val knownBySerial = scopes.mapValues { (_, scope) ->
            (AppCategories.BUILTINS + scope.categories.map { it.id }).toSet()
        }
        val edit = prefs.edit()
        edit.clear()
        // The validated backup mode restores with the taxonomy; replay is all-or-nothing.
        edit.putString("drawer_mode", if (mode == "all") "all" else "sections")
        scopes.forEach { (serial, scope) ->
            val known = knownBySerial.getValue(serial)
            edit.putStringSet(namesKey(serial), scope.categories.mapTo(HashSet()) { it.id })
            scope.categories.forEach { edit.putString(labelKey(serial, it.id), it.title) }
            edit.putString(orderKey(serial), scope.order.filter { it in known }.joinToString(","))
            edit.putString(selectedKey(serial), scope.selected.takeIf { it in known } ?: AppCategories.ALL)
        }
        assignments.forEach { (pkg, serial, category) ->
            if (pkg.isNotEmpty() && knownBySerial[serial]?.contains(category) == true) {
                edit.putString("assign|$pkg|$serial", category)
            }
        }
        check(edit.commit()) { "category restore write failed" }
    }

    fun drawerMode(): String = prefs.getString("drawer_mode", "sections") ?: "sections"
    fun setDrawerMode(mode: String) {
        prefs.edit().putString("drawer_mode", if (mode == "all") "all" else "sections").apply()
    }

    fun selectedCategory(serial: Long): String =
        prefs.getString(selectedKey(serial), AppCategories.ALL) ?: AppCategories.ALL
    fun setSelected(serial: Long, id: String) {
        prefs.edit().putString(selectedKey(serial), id.take(64)).apply()
    }

    /**
     * One-time reset for superseded development customization: the pre-profile global
     * taxonomy keys (`custom_names`, `custom_label|<id>`, `order`, `selected`) have no
     * recorded originating profile, so guessing ownership would leak work taxonomy into
     * personal exports and strand work assignments. Only these obsolete keys are cleared,
     * once, behind a marker; workspace data, per-profile taxonomy, assignments for known
     * profiles, and established v1 backup import are untouched.
     */
    fun clearLegacyDevState(): Boolean {
        // v2 reruns the corrected cleanup on installations already stamped by v1, whose
        // union-validated pass may have kept cross-profile orphans.
        if (prefs.getBoolean("legacy_reset_v2", false)) return false
        val edit = prefs.edit()
        var touched = false
        listOf("custom_names", "order", "selected").forEach { key ->
            if (prefs.contains(key)) {
                edit.remove(key)
                touched = true
            }
        }
        prefs.all.keys
            .filter { it.startsWith("custom_label|") && it.count { c -> c == '|' } == 1 }
            .forEach { edit.remove(it); touched = true }
        // Assignments pointing at removed legacy definitions would otherwise fall back
        // silently and be dropped on export; clear only those orphans here. Each
        // assignment is validated against its own profile's definitions, so a category
        // that exists only in another profile still counts as orphaned. Built-in
        // assignments and valid profile-scoped definitions are preserved.
        val customsBySerial = prefs.all.keys
            .filter { it.startsWith("custom_names|") }
            .associate { key ->
                (key.substringAfterLast("|").toLongOrNull() ?: -1L) to
                    prefs.getStringSet(key, emptySet()).orEmpty()
            }
        prefs.all.keys.filter { it.startsWith("assign|") }.forEach { key ->
            val serial = key.substringAfterLast("|").toLongOrNull()
            val valid = AppCategories.BUILTINS + customsBySerial.getOrDefault(serial, emptySet())
            if (prefs.getString(key, null) !in valid) {
                edit.remove(key)
                touched = true
            }
        }
        edit.putBoolean("legacy_reset_v2", true)
        edit.apply()
        return touched
    }

    // Serials with stored rows, used to defer destructive cleanup on cold start:
    // a transient lookup failure before first in-memory discovery must not
    // purge rows for a profile we have simply never seen this boot.
    fun knownSerials(): Set<Long> = prefs.all.keys.mapNotNull { k ->
        when {
            k.startsWith("assign|") -> k.substringAfterLast("|").toLongOrNull()
            k.startsWith("custom_names|") || k.startsWith("order|") || k.startsWith("selected|") ->
                k.substringAfterLast("|").toLongOrNull()
            k.startsWith("custom_label|") && k.count { c -> c == '|' } == 2 ->
                k.substringAfter("|").substringBefore("|").toLongOrNull()
            else -> null
        }
    }.toSet()

    fun retainProfiles(allowed: Set<Long>, preserve: Set<Long> = emptySet()) {
        // Purge legacy private and removed-profile rows, even when private apps are unlocked.
        // Provisionally classified profiles (preserve) keep their rows until discovery succeeds.
        val keep = allowed + preserve
        val edit = prefs.edit()
        prefs.all.keys.forEach { k ->
            when {
                k.startsWith("assign|") -> {
                    if (k.substringAfterLast("|").toLongOrNull() !in keep) edit.remove(k)
                }
                k.startsWith("custom_names|") || k.startsWith("order|") || k.startsWith("selected|") -> {
                    if (k.substringAfterLast("|").toLongOrNull() !in keep) edit.remove(k)
                }
                k.startsWith("custom_label|") && k.count { c -> c == '|' } == 2 -> {
                    if (k.substringAfter("|").substringBefore("|").toLongOrNull() !in keep) edit.remove(k)
                }
            }
        }
        edit.apply()
    }
}
