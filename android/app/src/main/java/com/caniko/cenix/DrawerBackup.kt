package com.caniko.cenix

import android.content.Context
import com.caniko.cenix.uniffi.BackupProfileRef
import com.caniko.cenix.uniffi.CategoryAssignment
import com.caniko.cenix.uniffi.DrawerBackup
import com.caniko.cenix.uniffi.DrawerCategory
import com.caniko.cenix.uniffi.DrawerTaxonomy
import com.caniko.cenix.uniffi.IconOverride
import com.caniko.cenix.uniffi.ProfileKind

/**
 * Maps drawer customization between durable stores (keyed by real profile serials)
 * and the backup interchange (logical personal slot plus optional work taxonomy).
 * Private profiles are never exported; validation rejects them on import.
 */
object DrawerBackupExport {
    fun empty(): DrawerBackup =
        DrawerBackup("sections", DrawerTaxonomy("all", emptyList(), emptyList()), null, emptyList(), "", emptyList())

    fun build(
        context: Context,
        profiles: List<BackupProfileRef>,
        includeWork: Boolean,
    ): DrawerBackup {
        val categories = CategoryStore(context)
        val packs = IconPackManager(context)
        fun logical(kind: ProfileKind): ULong? = when (kind) {
            ProfileKind.PERSONAL -> 0uL
            ProfileKind.WORK -> if (includeWork) 1uL else null
            ProfileKind.PRIVATE, ProfileKind.OTHER -> null
        }
        val bySerial = profiles.associate { it.profileId to it.kind }
        val personalSerial = profiles.singleOrNull { it.kind == ProfileKind.PERSONAL }?.profileId?.toLong()
        val workSerial = profiles.singleOrNull { it.kind == ProfileKind.WORK }?.profileId?.toLong()
        val assignments = categories.overrides().mapNotNull { (key, category) ->
            val packageName = key.substringBefore("|")
            val serial = key.substringAfter("|").toULongOrNull() ?: return@mapNotNull null
            val kind = bySerial[serial] ?: return@mapNotNull null
            val logicalId = logical(kind) ?: return@mapNotNull null
            CategoryAssignment(packageName, logicalId, category)
        }.sortedWith(compareBy({ it.`package` }, { it.profileId }))
        val overrides = packs.rawOverrides().mapNotNull { raw ->
            val kind = bySerial[raw.profileId] ?: return@mapNotNull null
            val logicalId = logical(kind) ?: return@mapNotNull null
            IconOverride(raw.`package`, raw.`class`, logicalId, raw.packPackage, raw.drawable)
        }.sortedWith(compareBy({ it.`package` }, { it.`class` }, { it.profileId }))
        fun taxonomy(serial: Long?): DrawerTaxonomy {
            if (serial == null) return DrawerTaxonomy("all", emptyList(), emptyList())
            return DrawerTaxonomy(
                categories.selectedCategory(serial),
                categories.customCategories(serial).map { DrawerCategory(it.id, it.name ?: it.id) },
                categories.orderedCategories(serial).map { it.id },
            )
        }
        return DrawerBackup(
            categories.drawerMode(),
            taxonomy(personalSerial),
            workSerial?.takeIf { includeWork }?.let(::taxonomy),
            assignments,
            packs.selectedPack().orEmpty(),
            overrides,
        )
    }

    /**
     * Replaces local drawer customization with the import plan's. Targets carry real
     * serials; rows for profiles absent from targets are dropped (omitted work).
     */
    fun apply(context: Context, drawer: DrawerBackup, targets: List<BackupProfileRef>) {
        replay(context, scoped(drawer, targets), targets)
    }

    internal fun scoped(drawer: DrawerBackup, targets: List<BackupProfileRef>): DrawerBackup = try {
        // Scoping policy is canonical in Rust (cenix-core backup module). The
        // pure-Kotlin body below runs only when native is unavailable.
        com.caniko.cenix.uniffi.scopeDrawerForTargets(drawer, targets)
    } catch (_: Throwable) {
        fallbackScoped(drawer, targets)
    }

    internal fun fallbackScoped(drawer: DrawerBackup, targets: List<BackupProfileRef>): DrawerBackup {
        require(targets.count { it.kind == ProfileKind.PERSONAL } == 1)
        val allowed = targets.filter { it.kind == ProfileKind.PERSONAL || it.kind == ProfileKind.WORK }
            .mapTo(HashSet()) { it.profileId }
        val keepWork = targets.any { it.kind == ProfileKind.WORK }
        return drawer.copy(
            work = drawer.work?.takeIf { keepWork },
            assignments = drawer.assignments.filter { it.profileId in allowed },
            iconOverrides = drawer.iconOverrides.filter { it.profileId in allowed },
        )
    }

    internal fun replay(context: Context, drawer: DrawerBackup, targets: List<BackupProfileRef>) {
        // Room keeps the scoped snapshot until both synchronous writes succeed. A crash
        // between the stores replays the same snapshot, rather than losing half the state.
        val personal = targets.single { it.kind == ProfileKind.PERSONAL }.profileId.toLong()
        val work = targets.singleOrNull { it.kind == ProfileKind.WORK }?.profileId?.toLong()
        val scopes = mutableMapOf(personal to drawer.personal)
        val workScope = drawer.work
        if (work != null && workScope != null) scopes[work] = workScope
        CategoryStore(context).replace(
            drawer.mode,
            scopes,
            drawer.assignments.map { Triple(it.`package`, it.profileId.toLong(), it.categoryId) },
        )
        // Absent packs are kept: render falls back to themed/default until installed.
        IconPackManager(context).replace(drawer.iconPack, drawer.iconOverrides)
    }
}
