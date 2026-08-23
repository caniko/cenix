package com.caniko.cenix

import com.caniko.cenix.db.CenixDatabase
import com.caniko.cenix.db.WorkspaceItemEntity

class Workspace(private val db: CenixDatabase) {
    fun items(): List<WorkspaceItemEntity> = db.dao().workspaceItems()

    fun pin(app: LaunchableApp, grid: PhoneGrid, screen: Int = 0): Boolean {
        if (items().any { it.sameApp(app) }) return true
        val cell = firstEmpty(grid, screen) ?: return false
        db.dao().insertWorkspace(
            WorkspaceItemEntity(
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

    fun unpin(app: LaunchableApp) {
        db.dao().deleteWorkspace(app.packageName, app.className, app.profileId)
    }

    fun dropMissing(live: Set<Triple<String, String, Long>>) {
        items().filter { Triple(it.packageName, it.className, it.profileId) !in live }
            .forEach { db.dao().deleteWorkspace(it.packageName, it.className, it.profileId) }
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

    private fun WorkspaceItemEntity.sameApp(app: LaunchableApp) =
        packageName == app.packageName && className == app.className && profileId == app.profileId
}
