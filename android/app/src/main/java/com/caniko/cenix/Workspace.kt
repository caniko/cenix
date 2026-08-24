package com.caniko.cenix

import com.caniko.cenix.db.CenixDatabase
import com.caniko.cenix.db.WorkspaceItemEntity

class Workspace(
    private val db: CenixDatabase,
    private val useNative: () -> Boolean = { true },
) {
    fun items(): List<WorkspaceItemEntity> = db.dao().workspaceItems()

    fun pin(app: LaunchableApp, grid: PhoneGrid, preferred: Int = 0): Boolean {
        if (items().any { it.sameApp(app) }) return true
        native("pin") { NativeWorkspace.pin(items(), app, grid, preferred) }?.let { return persist(it) }
        val start = preferred.coerceIn(0, SCREENS - 1)
        for (i in 0 until SCREENS) {
            val screen = (start + i) % SCREENS
            val cell = firstEmpty(grid, screen) ?: continue
            persist(
                items() + WorkspaceItemEntity(
                    screen = screen,
                    cellX = cell.first,
                    cellY = cell.second,
                    packageName = app.packageName,
                    className = app.className,
                    profileId = app.profileId,
                ),
            )
            return true
        }
        return false
    }

    fun dock(app: LaunchableApp, cols: Int): Boolean {
        val existing = items().firstOrNull { it.sameApp(app) }
        if (existing?.screen == HOTSEAT) return true
        native("dock") { NativeWorkspace.dock(items(), app, cols) }?.let { return persist(it) }
        val cellX = (0 until cols).firstOrNull { x -> items().none { it.screen == HOTSEAT && it.cellX == x } }
            ?: return false
        persist(
            items().filterNot { it.sameApp(app) } + WorkspaceItemEntity(
                screen = HOTSEAT,
                cellX = cellX,
                cellY = 0,
                packageName = app.packageName,
                className = app.className,
                profileId = app.profileId,
            ),
        )
        return true
    }

    fun place(app: LaunchableApp, screen: Int, cellX: Int, cellY: Int, cols: Int = 8, rows: Int = 8): Boolean {
        native("place") { NativeWorkspace.place(items(), app, screen, cellX, cellY, cols, rows) }?.let {
            return persist(it)
        }
        val all = items()
        val occupant = all.firstOrNull { it.screen == screen && it.cellX == cellX && it.cellY == cellY }
        if (occupant != null && !occupant.sameApp(app)) return false
        val existing = all.firstOrNull { it.sameApp(app) }
        val next = when {
            existing == null ->
                all + WorkspaceItemEntity(
                    screen = screen,
                    cellX = cellX,
                    cellY = cellY,
                    packageName = app.packageName,
                    className = app.className,
                    profileId = app.profileId,
                )
            occupant?.sameApp(app) == true -> all
            else -> all.map {
                if (it.sameApp(app)) it.copy(screen = screen, cellX = cellX, cellY = cellY) else it
            }
        }
        persist(next)
        return true
    }

    fun unpin(app: LaunchableApp) {
        native("remove") { NativeWorkspace.remove(items(), app) }?.let { persist(it); return }
        persist(items().filterNot { it.sameApp(app) })
    }

    fun dropMissing(live: Set<Triple<String, String, Long>>) {
        native("drop") { NativeWorkspace.dropMissing(items(), live) }?.let { persist(it); return }
        persist(items().filter { Triple(it.packageName, it.className, it.profileId) in live })
    }

    fun firstEmpty(grid: PhoneGrid, screen: Int = 0): Pair<Int, Int>? {
        val taken = items().filter { it.screen == screen }.map { it.cellX to it.cellY }.toSet()
        for (y in 0 until grid.rows) {
            for (x in 0 until grid.cols) {
                if (x to y !in taken) return x to y
            }
        }
        return null
    }

    private fun persist(items: List<WorkspaceItemEntity>): Boolean {
        db.dao().replaceWorkspace(items)
        CenixLog.event(EventId.WORKSPACE_TX, Severity.INFO, mapOf("count" to items.size.toString()))
        return true
    }

    private fun native(op: String, block: () -> List<WorkspaceItemEntity>?): List<WorkspaceItemEntity>? {
        if (!useNative()) return null
        return try {
            Class.forName("com.caniko.cenix.NativeWorkspace")
            block()
        } catch (_: Throwable) {
            CenixLog.event(EventId.WORKSPACE_TX, Severity.WARN, mapOf("result" to "local", "op" to op))
            null
        }
    }

    private fun WorkspaceItemEntity.sameApp(app: LaunchableApp) =
        packageName == app.packageName && className == app.className && profileId == app.profileId

    companion object {
        const val SCREENS = 2
        const val HOTSEAT = -1
    }
}
