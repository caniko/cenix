package com.caniko.cenix

import com.caniko.cenix.uniffi.WorkspaceCommand
import com.caniko.cenix.uniffi.CellRect
import com.caniko.cenix.uniffi.ComponentId
import com.caniko.cenix.uniffi.ContainerRef
import com.caniko.cenix.uniffi.GridSpec
import com.caniko.cenix.uniffi.ShortcutId
import com.caniko.cenix.uniffi.WorkspaceException
import com.caniko.cenix.uniffi.WorkspaceSnapshot
import com.caniko.cenix.uniffi.WorkspaceTransition
import com.caniko.cenix.uniffi.WidgetProviderId
import com.caniko.cenix.uniffi.WidgetMinimumSpan

class WorkspaceController(
    private val repository: LauncherRepository,
    private val nativeAvailable: () -> Boolean,
) {
    fun snapshot(): WorkspaceSnapshot = repository.snapshot()

    fun placeFromAllApps(app: LaunchableApp, pageId: ULong, cellX: Int, cellY: Int): WorkspaceTransition? {
        val state = snapshot()
        return execute(
            "place",
            WorkspaceCommand.PlaceFromAllApps(
                state.generation,
                repository.nextItemId(),
                ComponentId(app.packageName, app.className, app.profileId.toULong()),
                pageId,
                CellRect(cellX, cellY, 1, 1),
            ),
        )
    }

    fun placeShortcut(shortcut: ShortcutId, container: ContainerRef, cellX: Int, cellY: Int): WorkspaceTransition? {
        val state = snapshot()
        return execute(
            "shortcut-place",
            WorkspaceCommand.PlaceShortcut(
                state.generation,
                repository.nextItemId(),
                shortcut,
                container,
                CellRect(cellX, cellY, 1, 1),
            ),
        )
    }

    fun placeWidget(
        itemId: ULong,
        provider: WidgetProviderId,
        appWidgetId: Int,
        pageId: ULong,
        cell: CellRect,
    ): WorkspaceTransition? {
        val state = snapshot()
        if (!nativeAvailable()) return null
        val command = WorkspaceCommand.PlaceWidget(state.generation, itemId, provider, pageId, cell)
        return try {
            repository.applyWidget(command, itemId, appWidgetId)
        } catch (error: WorkspaceException) {
            CenixLog.event(
                EventId.WORKSPACE_TRANSITION,
                Severity.WARN,
                mapOf("result" to "rejected", "category" to error.javaClass.simpleName),
            )
            null
        }
    }

    fun resizeWidget(itemId: ULong, cell: CellRect): WorkspaceTransition? {
        val state = snapshot()
        return execute("widget-resize", WorkspaceCommand.ResizeWidget(state.generation, itemId, cell))
    }

    fun move(itemId: ULong, container: ContainerRef, cellX: Int, cellY: Int, reorder: Boolean = true): WorkspaceTransition? {
        val state = snapshot()
        val source = state.items.firstOrNull { it.itemId == itemId } ?: return null
        val cell = CellRect(cellX, cellY, source.cell.spanX, source.cell.spanY)
        val command = if (reorder) WorkspaceCommand.Reorder(state.generation, itemId, container, cell)
        else WorkspaceCommand.Move(state.generation, itemId, container, cell)
        return execute("move", command)
    }

    fun remove(itemId: ULong): WorkspaceTransition? {
        val state = snapshot()
        return execute("remove", WorkspaceCommand.Remove(state.generation, itemId))
    }

    fun createFolder(sourceItemId: ULong, destinationItemId: ULong): WorkspaceTransition? {
        val state = snapshot()
        return execute(
            "folder-create",
            WorkspaceCommand.CreateFolder(
                state.generation,
                repository.nextItemId(),
                sourceItemId,
                destinationItemId,
            ),
        )
    }

    fun addFromAllAppsToFolder(app: LaunchableApp, folderId: ULong, rank: UInt): WorkspaceTransition? {
        val state = snapshot()
        return execute(
            "folder-add",
            WorkspaceCommand.AddFromAllAppsToFolder(
                state.generation,
                repository.nextItemId(),
                ComponentId(app.packageName, app.className, app.profileId.toULong()),
                folderId,
                rank,
            ),
        )
    }

    fun addShortcutToFolder(shortcut: ShortcutId, folderId: ULong, rank: UInt): WorkspaceTransition? {
        val state = snapshot()
        return execute(
            "shortcut-folder-add",
            WorkspaceCommand.AddShortcutToFolder(
                state.generation,
                repository.nextItemId(),
                shortcut,
                folderId,
                rank,
            ),
        )
    }

    fun addItemToFolder(itemId: ULong, folderId: ULong, rank: UInt): WorkspaceTransition? {
        val state = snapshot()
        return execute("folder-add", WorkspaceCommand.AddItemToFolder(state.generation, itemId, folderId, rank))
    }

    fun moveFolderMember(folderId: ULong, itemId: ULong, rank: UInt): WorkspaceTransition? {
        val state = snapshot()
        return execute("folder-reorder", WorkspaceCommand.MoveFolderMember(state.generation, folderId, itemId, rank))
    }

    fun removeItemFromFolder(
        folderId: ULong,
        itemId: ULong,
        container: ContainerRef,
        cellX: Int,
        cellY: Int,
    ): WorkspaceTransition? {
        val state = snapshot()
        return execute(
            "folder-remove",
            WorkspaceCommand.RemoveItemFromFolder(
                state.generation,
                folderId,
                itemId,
                container,
                CellRect(cellX, cellY, 1, 1),
            ),
        )
    }

    fun renameFolder(folderId: ULong, title: String): WorkspaceTransition? {
        val state = snapshot()
        return execute("folder-rename", WorkspaceCommand.RenameFolder(state.generation, folderId, title))
    }

    fun dock(itemId: ULong): WorkspaceTransition? {
        val state = snapshot()
        val occupied = state.items.filter { it.container is ContainerRef.Hotseat }.map { it.cell.cellX }.toSet()
        val rank = (0 until state.grid.hotseatCols).firstOrNull { it !in occupied } ?: return null
        return execute("dock", WorkspaceCommand.Dock(state.generation, itemId, rank))
    }

    fun undock(itemId: ULong, pageId: ULong): WorkspaceTransition? {
        val state = snapshot()
        return execute("undock", WorkspaceCommand.Undock(state.generation, itemId, pageId, CellRect(0, 0, 1, 1)))
    }

    fun addPage(): WorkspaceTransition? {
        val state = snapshot()
        return execute("add-page", WorkspaceCommand.AddPage(state.generation, repository.nextPageId()))
    }

    fun setGrid(gridOption: PhoneGrid, widgetMinimumSpans: List<WidgetMinimumSpan>): WorkspaceTransition? {
        val state = snapshot()
        val grid = GridSpec(gridOption.cols, gridOption.rows, gridOption.cols)
        if (state.grid == grid && repository.selectedGridName() == gridOption.name) return null
        if (!nativeAvailable()) return null
        val command = WorkspaceCommand.SetGrid(
            state.generation,
            grid,
            repository.nextPageId(),
            widgetMinimumSpans,
        )
        CenixLog.event(EventId.WORKSPACE_COMMAND, Severity.INFO, mapOf("category" to "set-grid"))
        return try {
            repository.applyGrid(command, gridOption.name)
        } catch (error: WorkspaceException) {
            CenixLog.event(
                EventId.WORKSPACE_TRANSITION,
                Severity.WARN,
                mapOf("result" to "rejected", "category" to error.javaClass.simpleName),
            )
            throw error
        }
    }

    fun dropMissing(live: Collection<LaunchableApp>, authoritativeProfiles: Collection<Long>): WorkspaceTransition? {
        val state = snapshot()
        val ids = live.map { ComponentId(it.packageName, it.className, it.profileId.toULong()) }
        return execute(
            "drop-missing",
            WorkspaceCommand.DropMissing(state.generation, ids, authoritativeProfiles.map(Long::toULong)),
        )
    }

    fun reconcileShortcuts(live: Collection<ShortcutId>, authoritativeProfiles: Collection<Long>): WorkspaceTransition? {
        val state = snapshot()
        return execute(
            "shortcut-reconcile",
            WorkspaceCommand.ReconcileShortcuts(
                state.generation,
                live.toList(),
                authoritativeProfiles.map(Long::toULong),
            ),
        )
    }

    fun removeProfiles(profileIds: Collection<Long>): WorkspaceTransition? {
        if (profileIds.isEmpty()) return null
        val state = snapshot()
        return execute("profile-remove", WorkspaceCommand.RemoveProfiles(state.generation, profileIds.map(Long::toULong)))
    }

    fun execute(category: String, command: WorkspaceCommand): WorkspaceTransition? {
        if (!nativeAvailable()) return null
        CenixLog.event(EventId.WORKSPACE_COMMAND, Severity.INFO, mapOf("category" to category))
        return try {
            repository.apply(command).also { transition ->
                CenixLog.event(
                    EventId.WORKSPACE_TRANSITION,
                    Severity.INFO,
                    mapOf(
                        "result" to "accepted",
                        "createdPages" to transition.createdPageIds.size.toString(),
                        "removedPages" to transition.removedPageIds.size.toString(),
                    ),
                )
            }
        } catch (error: WorkspaceException) {
            CenixLog.event(
                EventId.WORKSPACE_TRANSITION,
                Severity.WARN,
                mapOf("result" to "rejected", "category" to error.javaClass.simpleName),
            )
            null
        }
    }
}
