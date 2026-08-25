package com.caniko.cenix

import com.caniko.cenix.db.CenixDatabase
import com.caniko.cenix.db.ApplicationItemEntity
import com.caniko.cenix.db.FolderEntity
import com.caniko.cenix.db.FolderMemberEntity
import com.caniko.cenix.db.WorkspaceItemEntity
import com.caniko.cenix.db.WorkspaceMetadataEntity
import com.caniko.cenix.db.WorkspacePageEntity
import com.caniko.cenix.uniffi.CellRect
import com.caniko.cenix.uniffi.ComponentId
import com.caniko.cenix.uniffi.ContainerRef
import com.caniko.cenix.uniffi.Folder
import com.caniko.cenix.uniffi.FolderMember
import com.caniko.cenix.uniffi.GridSpec
import com.caniko.cenix.uniffi.ItemPayload
import com.caniko.cenix.uniffi.WorkspaceCommand
import com.caniko.cenix.uniffi.WorkspaceItem
import com.caniko.cenix.uniffi.WorkspacePage
import com.caniko.cenix.uniffi.WorkspaceSnapshot
import com.caniko.cenix.uniffi.WorkspaceTransition

class LauncherRepository(private val db: CenixDatabase) {
    fun snapshot(): WorkspaceSnapshot {
        val rows = db.dao().workspaceState()
        val applications = rows.applications.associateBy { it.itemId }
        val members = rows.folderMembers.groupBy { it.folderId }
        return WorkspaceSnapshot(
            generation = rows.metadata.generation.toULong(),
            grid = GridSpec(rows.metadata.cols, rows.metadata.rows, rows.metadata.hotseatCols),
            pages = rows.pages.map { WorkspacePage(it.pageId.toULong(), it.rank) },
            items = rows.items.map { toModel(it, applications) },
            folders = rows.folders.map { folder ->
                Folder(
                    folder.folderId.toULong(),
                    folder.title,
                    members[folder.folderId].orEmpty().map { member ->
                        FolderMember(
                            member.itemId.toULong(),
                            checkNotNull(applications[member.itemId]).toComponent(),
                            member.rank.toUInt(),
                        )
                    },
                )
            },
        ).also {
            CenixLog.event(EventId.WORKSPACE_SNAPSHOT, Severity.INFO, mapOf("items" to it.items.size.toString(), "pages" to it.pages.size.toString()))
        }
    }

    fun apply(command: WorkspaceCommand): WorkspaceTransition {
        val before = snapshot()
        val currentMetadata = checkNotNull(db.dao().workspaceMetadata())
        val started = System.nanoTime()
        val transition = NativeWorkspace.apply(before, command)
        val memberIds = transition.folders.flatMap { it.members }.map { it.itemId }
        val maxItemId = (transition.items.map { it.itemId } + memberIds).maxOrNull() ?: 0UL
        val applicationItems = transition.items.mapNotNull { item ->
            (item.payload as? ItemPayload.Application)?.let { item.itemId to it.component }
        } + transition.folders.flatMap { folder -> folder.members.map { it.itemId to it.component } }
        try {
            db.dao().commitWorkspace(
                expectedGeneration = before.generation.toLong(),
                metadata = WorkspaceMetadataEntity(
                    generation = transition.generation.toLong(),
                    cols = transition.grid.cols,
                    rows = transition.grid.rows,
                    hotseatCols = transition.grid.hotseatCols,
                    nextItemId = maxOf(currentMetadata.nextItemId, maxItemId.toLong() + 1),
                    nextPageId = maxOf(currentMetadata.nextPageId, (transition.pages.maxOfOrNull { it.pageId } ?: 0UL).toLong() + 1),
                ),
                pages = transition.pages.map { WorkspacePageEntity(it.pageId.toLong(), it.rank) },
                items = transition.items.map(::toEntity),
                applications = applicationItems.map { (itemId, component) -> component.toEntity(itemId) }.sortedBy { it.itemId },
                folders = transition.folders.map { FolderEntity(it.folderId.toLong(), it.title) },
                folderMembers = transition.folders.flatMap { folder ->
                    folder.members.map { FolderMemberEntity(it.itemId.toLong(), folder.folderId.toLong(), it.rank.toInt()) }
                },
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

    private fun toModel(entity: WorkspaceItemEntity, applications: Map<Long, ApplicationItemEntity>): WorkspaceItem = WorkspaceItem(
        itemId = entity.id.toULong(),
        payload = if (entity.itemKind == WorkspaceItemEntity.ITEM_FOLDER) {
            ItemPayload.Folder
        } else {
            ItemPayload.Application(checkNotNull(applications[entity.id]).toComponent())
        },
        container = if (entity.containerKind == WorkspaceItemEntity.CONTAINER_HOTSEAT) {
            ContainerRef.Hotseat
        } else {
            ContainerRef.Workspace(entity.containerId.toULong())
        },
        cell = CellRect(entity.cellX, entity.cellY, entity.spanX, entity.spanY),
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
            itemKind = if (item.payload is ItemPayload.Folder) WorkspaceItemEntity.ITEM_FOLDER else WorkspaceItemEntity.ITEM_APPLICATION,
        )
    }

    private fun ApplicationItemEntity.toComponent() = ComponentId(packageName, className, profileId.toULong())

    private fun ComponentId.toEntity(itemId: ULong) = ApplicationItemEntity(
        itemId.toLong(),
        `package`,
        `class`,
        profileId.toLong(),
    )

    private fun durationBucket(nanos: Long): String = when {
        nanos < 5_000_000 -> "lt5ms"
        nanos < 20_000_000 -> "lt20ms"
        nanos < 100_000_000 -> "lt100ms"
        else -> "gte100ms"
    }
}
