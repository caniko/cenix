package com.caniko.cenix

import com.caniko.cenix.uniffi.WorkspaceCommand
import com.caniko.cenix.uniffi.CellRect
import com.caniko.cenix.uniffi.ComponentId
import com.caniko.cenix.uniffi.ContainerRef
import com.caniko.cenix.uniffi.GridSpec
import com.caniko.cenix.uniffi.WorkspaceException
import com.caniko.cenix.uniffi.WorkspaceSnapshot
import com.caniko.cenix.uniffi.WorkspaceTransition

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

    fun move(itemId: ULong, container: ContainerRef, cellX: Int, cellY: Int, reorder: Boolean = true): WorkspaceTransition? {
        val state = snapshot()
        val cell = CellRect(cellX, cellY, 1, 1)
        val command = if (reorder) WorkspaceCommand.Reorder(state.generation, itemId, container, cell)
        else WorkspaceCommand.Move(state.generation, itemId, container, cell)
        return execute("move", command)
    }

    fun remove(itemId: ULong): WorkspaceTransition? {
        val state = snapshot()
        return execute("remove", WorkspaceCommand.Remove(state.generation, itemId))
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

    fun setGrid(cols: Int, rows: Int): WorkspaceTransition? {
        val state = snapshot()
        val grid = GridSpec(cols, rows, cols)
        if (state.grid == grid) return null
        return execute("set-grid", WorkspaceCommand.SetGrid(state.generation, grid))
    }

    fun dropMissing(live: Collection<LaunchableApp>): WorkspaceTransition? {
        val state = snapshot()
        val ids = live.map { ComponentId(it.packageName, it.className, it.profileId.toULong()) }
        return execute("drop-missing", WorkspaceCommand.DropMissing(state.generation, ids))
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
