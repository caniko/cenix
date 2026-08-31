package com.caniko.cenix

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.UserHandle
import com.caniko.cenix.uniffi.ShortcutId

data class LauncherShortcut(
    val id: ShortcutId,
    val label: String,
    val disabledMessage: String,
    val icon: Drawable?,
    val user: UserHandle,
    val enabled: Boolean,
    val manifest: Boolean,
    val dynamic: Boolean,
    val rank: Int,
)

class ShortcutCatalog(
    context: Context,
    private val apps: AppCatalog,
    private val profiles: ProfileController,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val packageManager = context.packageManager
    private val density = context.resources.displayMetrics.densityDpi
    private val pinned = mutableMapOf<Pair<String, ULong>, List<String>>()

    fun published(app: LaunchableApp): List<LauncherShortcut> {
        if (!profiles.isAvailable(app.profileId)) return emptyList()
        val user = app.user ?: apps.userForSerial(app.profileId) ?: return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(app.packageName)
            .setActivity(ComponentName(app.packageName, app.className))
            .setQueryFlags(PUBLISHED)
        return query(query, user).sortedWith(compareBy<ShortcutInfo>({ !it.isDeclaredInManifest }, { it.rank }, { it.id }))
            .let(::limitPublished)
            .mapNotNull { toModel(it, app.profileId) }
    }

    fun resolve(ids: Collection<ShortcutId>): Map<ShortcutId, LauncherShortcut> = ids
        .groupBy { it.`package` to it.profileId }
        .flatMap { (key, requested) ->
            if (!profiles.isAvailable(key.second.toLong())) return@flatMap emptyList()
            val user = apps.userForSerial(key.second.toLong()) ?: return@flatMap emptyList()
            val query = LauncherApps.ShortcutQuery()
                .setPackage(key.first)
                .setShortcutIds(requested.map { it.shortcutId })
                .setQueryFlags(ALL)
            query(query, user).mapNotNull { toModel(it, key.second.toLong()) }.filter { it.enabled }
        }.associateBy { it.id }

    fun launch(shortcut: LauncherShortcut, source: Rect? = null): Boolean {
        if (!shortcut.enabled || !profiles.isAvailable(shortcut.id.profileId.toLong())) return false
        return try {
            launcherApps.startShortcut(
                shortcut.id.`package`,
                shortcut.id.shortcutId,
                source,
                null,
                shortcut.user,
            )
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun pin(identities: Collection<ShortcutId>) {
        val desired = identities.groupBy { it.`package` to it.profileId }
            .mapValues { (_, values) -> values.map { it.shortcutId }.distinct().sorted() }
        (pinned.keys + desired.keys).forEach { key ->
            val shortcutIds = desired[key].orEmpty()
            if (pinned[key] == shortcutIds) return@forEach
            if (!profiles.isAvailable(key.second.toLong())) return@forEach
            apps.userForSerial(key.second.toLong())?.let { user ->
                try {
                    launcherApps.pinShortcuts(key.first, shortcutIds, user)
                    pinned[key] = shortcutIds
                } catch (_: RuntimeException) {
                    Unit
                }
            }
        }
    }

    private fun query(query: LauncherApps.ShortcutQuery, user: UserHandle): List<ShortcutInfo> = try {
        launcherApps.getShortcuts(query, user).orEmpty()
    } catch (_: RuntimeException) {
        emptyList()
    }

    private fun toModel(info: ShortcutInfo, profileId: Long): LauncherShortcut? {
        if (info.`package`.isBlank() || info.id.isBlank()) return null
        val icon = try {
            launcherApps.getShortcutIconDrawable(info, density)?.let { packageManager.getUserBadgedIcon(it, info.userHandle) }
        } catch (_: RuntimeException) {
            null
        }
        return LauncherShortcut(
            id = ShortcutId(info.`package`, info.id, profileId.toULong()),
            label = safeLabel(info.longLabel?.takeIf { it.isNotBlank() } ?: info.shortLabel),
            disabledMessage = safeLabel(info.disabledMessage),
            icon = icon,
            user = info.userHandle,
            enabled = info.isEnabled,
            manifest = info.isDeclaredInManifest,
            dynamic = info.isDynamic,
            rank = info.rank,
        )
    }

    private fun limitPublished(sorted: List<ShortcutInfo>): List<ShortcutInfo> {
        if (sorted.size <= MAX_SHORTCUTS) return sorted
        val result = sorted.take(MAX_SHORTCUTS).toMutableList()
        var dynamic = result.count(ShortcutInfo::isDynamic)
        for (shortcut in sorted.drop(MAX_SHORTCUTS)) {
            if (shortcut.isDynamic && dynamic < MIN_DYNAMIC) {
                val replace = result.indexOfLast { !it.isDynamic }
                if (replace >= 0) {
                    result[replace] = shortcut
                    dynamic++
                }
            }
        }
        return result
    }

    companion object {
        private const val MAX_SHORTCUTS = 4
        private const val MIN_DYNAMIC = 2
        private const val PUBLISHED = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
        private const val ALL = PUBLISHED or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED

        fun safeLabel(value: CharSequence?): String {
            val clean = value?.toString().orEmpty()
                .filterNot { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }
            val points = clean.codePoints().limit(80).toArray()
            return String(points, 0, points.size)
        }
    }
}
