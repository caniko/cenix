package com.caniko.cenix

import com.caniko.cenix.db.WorkspaceItemEntity
import com.caniko.cenix.uniffi.AppId
import com.caniko.cenix.uniffi.WorkspaceCommand
import com.caniko.cenix.uniffi.WorkspaceException
import com.caniko.cenix.uniffi.WorkspaceItem
import com.caniko.cenix.uniffi.WorkspaceSnapshot
import com.caniko.cenix.uniffi.applyWorkspaceCommand

object NativeWorkspace {
    @JvmStatic
    fun pin(items: List<WorkspaceItemEntity>, app: LaunchableApp, grid: PhoneGrid, preferred: Int): List<WorkspaceItemEntity>? =
        apply(
            items,
            grid.cols,
            grid.rows,
            WorkspaceCommand.Pin(app.packageName, app.className, app.profileId.toULong(), preferred),
        )

    @JvmStatic
    fun dock(items: List<WorkspaceItemEntity>, app: LaunchableApp, cols: Int): List<WorkspaceItemEntity>? =
        apply(
            items,
            cols,
            1,
            WorkspaceCommand.Dock(app.packageName, app.className, app.profileId.toULong()),
        )

    @JvmStatic
    fun place(
        items: List<WorkspaceItemEntity>,
        app: LaunchableApp,
        screen: Int,
        cellX: Int,
        cellY: Int,
        cols: Int,
        rows: Int,
    ): List<WorkspaceItemEntity>? =
        apply(
            items,
            cols,
            if (screen == Workspace.HOTSEAT) 1 else rows,
            WorkspaceCommand.Place(app.packageName, app.className, app.profileId.toULong(), screen, cellX, cellY),
        )

    @JvmStatic
    fun remove(items: List<WorkspaceItemEntity>, app: LaunchableApp): List<WorkspaceItemEntity>? =
        apply(
            items,
            1,
            1,
            WorkspaceCommand.Remove(app.packageName, app.className, app.profileId.toULong()),
        )

    @JvmStatic
    fun dropMissing(items: List<WorkspaceItemEntity>, live: Set<Triple<String, String, Long>>): List<WorkspaceItemEntity>? =
        apply(
            items,
            1,
            1,
            WorkspaceCommand.DropMissing(
                live.map { (pkg, cls, profile) -> AppId(pkg, cls, profile.toULong()) },
            ),
        )

    private fun apply(
        items: List<WorkspaceItemEntity>,
        cols: Int,
        rows: Int,
        command: WorkspaceCommand,
    ): List<WorkspaceItemEntity>? {
        val snapshot = WorkspaceSnapshot(
            items.map {
                WorkspaceItem(it.packageName, it.className, it.profileId.toULong(), it.screen, it.cellX, it.cellY)
            },
            cols,
            rows,
            Workspace.SCREENS,
        )
        return try {
            applyWorkspaceCommand(snapshot, command).items.map {
                WorkspaceItemEntity(
                    screen = it.screen,
                    cellX = it.cellX,
                    cellY = it.cellY,
                    packageName = it.`package`,
                    className = it.`class`,
                    profileId = it.profileId.toLong(),
                )
            }
        } catch (_: WorkspaceException) {
            null
        }
    }
}
