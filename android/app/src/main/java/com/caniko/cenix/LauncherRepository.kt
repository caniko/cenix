package com.caniko.cenix

import com.caniko.cenix.db.CenixDatabase
import com.caniko.cenix.db.WorkspaceItemEntity
import com.caniko.cenix.db.WorkspaceMetadataEntity
import com.caniko.cenix.db.WorkspacePageEntity
import com.caniko.cenix.uniffi.CellRect
import com.caniko.cenix.uniffi.ComponentId
import com.caniko.cenix.uniffi.ContainerRef
import com.caniko.cenix.uniffi.GridSpec
import com.caniko.cenix.uniffi.ItemKind
import com.caniko.cenix.uniffi.WorkspaceCommand
import com.caniko.cenix.uniffi.WorkspaceItem
import com.caniko.cenix.uniffi.WorkspacePage
import com.caniko.cenix.uniffi.WorkspaceSnapshot
import com.caniko.cenix.uniffi.WorkspaceTransition

class LauncherRepository(private val db: CenixDatabase) {
    fun snapshot(): WorkspaceSnapshot {
        val rows = db.dao().workspaceState()
        return WorkspaceSnapshot(
            generation = rows.metadata.generation.toULong(),
            grid = GridSpec(rows.metadata.cols, rows.metadata.rows, rows.metadata.hotseatCols),
            pages = rows.pages.map { WorkspacePage(it.pageId.toULong(), it.rank) },
            items = rows.items.map(::toModel),
        ).also {
            CenixLog.event(EventId.WORKSPACE_SNAPSHOT, Severity.INFO, mapOf("items" to it.items.size.toString(), "pages" to it.pages.size.toString()))
        }
    }

    fun apply(command: WorkspaceCommand): WorkspaceTransition {
        val before = snapshot()
        val currentMetadata = checkNotNull(db.dao().workspaceMetadata())
        val started = System.nanoTime()
        val transition = NativeWorkspace.apply(before, command)
        try {
            db.dao().commitWorkspace(
                expectedGeneration = before.generation.toLong(),
                metadata = WorkspaceMetadataEntity(
                    generation = transition.generation.toLong(),
                    cols = transition.grid.cols,
                    rows = transition.grid.rows,
                    hotseatCols = transition.grid.hotseatCols,
                    nextItemId = maxOf(currentMetadata.nextItemId, (transition.items.maxOfOrNull { it.itemId } ?: 0UL).toLong() + 1),
                    nextPageId = maxOf(currentMetadata.nextPageId, (transition.pages.maxOfOrNull { it.pageId } ?: 0UL).toLong() + 1),
                ),
                pages = transition.pages.map { WorkspacePageEntity(it.pageId.toLong(), it.rank) },
                items = transition.items.map(::toEntity),
            )
            CenixLog.event(
                EventId.ROOM_COMMIT,
                Severity.INFO,
                mapOf("duration" to durationBucket(System.nanoTime() - started), "changed" to transition.changedItemIds.size.toString()),
            )
            return transition
        } catch (error: Throwable) {
            CenixLog.event(EventId.ROOM_ROLLBACK, Severity.ERROR, mapOf("category" to error.javaClass.simpleName))
            throw error
        }
    }

    fun nextItemId(): ULong = checkNotNull(db.dao().workspaceMetadata()).nextItemId.toULong()

    fun nextPageId(): ULong = checkNotNull(db.dao().workspaceMetadata()).nextPageId.toULong()

    private fun toModel(entity: WorkspaceItemEntity): WorkspaceItem = WorkspaceItem(
        itemId = entity.id.toULong(),
        component = ComponentId(entity.packageName, entity.className, entity.profileId.toULong()),
        container = if (entity.containerKind == WorkspaceItemEntity.CONTAINER_HOTSEAT) {
            ContainerRef.Hotseat
        } else {
            ContainerRef.Workspace(entity.containerId.toULong())
        },
        cell = CellRect(entity.cellX, entity.cellY, entity.spanX, entity.spanY),
        kind = ItemKind.APPLICATION,
    )

    private fun toEntity(item: WorkspaceItem): WorkspaceItemEntity {
        val container = item.container
        return WorkspaceItemEntity(
            id = item.itemId.toLong(),
            containerKind = if (container is ContainerRef.Hotseat) WorkspaceItemEntity.CONTAINER_HOTSEAT else WorkspaceItemEntity.CONTAINER_WORKSPACE,
            containerId = (container as? ContainerRef.Workspace)?.pageId?.toLong() ?: 0,
            cellX = item.cell.cellX,
            cellY = item.cell.cellY,
            spanX = item.cell.spanX,
            spanY = item.cell.spanY,
            packageName = item.component.`package`,
            className = item.component.`class`,
            profileId = item.component.profileId.toLong(),
        )
    }

    private fun durationBucket(nanos: Long): String = when {
        nanos < 5_000_000 -> "lt5ms"
        nanos < 20_000_000 -> "lt20ms"
        nanos < 100_000_000 -> "lt100ms"
        else -> "gte100ms"
    }
}
