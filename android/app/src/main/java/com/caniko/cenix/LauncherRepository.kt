package com.caniko.cenix

import com.caniko.cenix.db.CenixDatabase
import com.caniko.cenix.db.ApplicationItemEntity
import com.caniko.cenix.db.FolderEntity
import com.caniko.cenix.db.FolderMemberEntity
import com.caniko.cenix.db.ShortcutItemEntity
import com.caniko.cenix.db.WorkspaceItemEntity
import com.caniko.cenix.db.WidgetItemEntity
import com.caniko.cenix.db.WorkspaceMetadataEntity
import com.caniko.cenix.db.WorkspacePageEntity
import com.caniko.cenix.uniffi.CellRect
import com.caniko.cenix.uniffi.ComponentId
import com.caniko.cenix.uniffi.ContainerRef
import com.caniko.cenix.uniffi.Folder
import com.caniko.cenix.uniffi.FolderMember
import com.caniko.cenix.uniffi.GridSpec
import com.caniko.cenix.uniffi.ItemPayload
import com.caniko.cenix.uniffi.ShortcutId
import com.caniko.cenix.uniffi.WorkspaceCommand
import com.caniko.cenix.uniffi.WorkspaceItem
import com.caniko.cenix.uniffi.WorkspacePage
import com.caniko.cenix.uniffi.WorkspaceSnapshot
import com.caniko.cenix.uniffi.WorkspaceTransition
import com.caniko.cenix.uniffi.WidgetProviderId

class LauncherRepository(private val db: CenixDatabase) {
    fun snapshot(): WorkspaceSnapshot {
        val rows = db.dao().workspaceState()
        val applications = rows.applications.associateBy { it.itemId }
        val shortcuts = rows.shortcuts.associateBy { it.itemId }
        val widgets = rows.widgets.associateBy { it.itemId }
        val members = rows.folderMembers.groupBy { it.folderId }
        return WorkspaceSnapshot(
            generation = rows.metadata.generation.toULong(),
            grid = GridSpec(rows.metadata.cols, rows.metadata.rows, rows.metadata.hotseatCols),
            pages = rows.pages.map { WorkspacePage(it.pageId.toULong(), it.rank) },
            items = rows.items.map { toModel(it, applications, shortcuts, widgets) },
            folders = rows.folders.map { folder ->
                Folder(
                    folder.folderId.toULong(),
                    folder.title,
                    members[folder.folderId].orEmpty().map { member ->
                        FolderMember(
                            member.itemId.toULong(),
                            payloadFor(member.itemId, applications, shortcuts, widgets),
                            member.rank.toUInt(),
                        )
                    },
                )
            },
        ).also {
            CenixLog.event(EventId.WORKSPACE_SNAPSHOT, Severity.INFO, mapOf("items" to it.items.size.toString(), "pages" to it.pages.size.toString()))
        }
    }

    fun apply(command: WorkspaceCommand): WorkspaceTransition = apply(command, null, null)

    fun applyWidget(command: WorkspaceCommand, itemId: ULong, appWidgetId: Int): WorkspaceTransition =
        apply(command, itemId, appWidgetId)

    private fun apply(command: WorkspaceCommand, boundItemId: ULong?, appWidgetId: Int?): WorkspaceTransition {
        val before = snapshot()
        val currentMetadata = checkNotNull(db.dao().workspaceMetadata())
        val existingWidgets = db.dao().workspaceWidgets().associateBy { it.itemId }
        val started = System.nanoTime()
        val transition = NativeWorkspace.apply(before, command)
        val memberIds = transition.folders.flatMap { it.members }.map { it.itemId }
        val maxItemId = (transition.items.map { it.itemId } + memberIds).maxOrNull() ?: 0UL
        val payloads = transition.items.map { it.itemId to it.payload } +
            transition.folders.flatMap { folder -> folder.members.map { it.itemId to it.payload } }
        val applicationItems = payloads.mapNotNull { (itemId, payload) ->
            (payload as? ItemPayload.Application)?.let { itemId to it.component }
        }
        val shortcutItems = payloads.mapNotNull { (itemId, payload) ->
            (payload as? ItemPayload.Shortcut)?.let { itemId to it.shortcut }
        }
        val widgetItems = payloads.mapNotNull { (itemId, payload) ->
            (payload as? ItemPayload.Widget)?.let {
                WidgetItemEntity(
                    itemId.toLong(),
                    it.provider.`package`,
                    it.provider.`class`,
                    it.provider.profileId.toLong(),
                    if (itemId == boundItemId) appWidgetId else existingWidgets[itemId.toLong()]?.appWidgetId,
                )
            }
        }
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
                shortcuts = shortcutItems.map { (itemId, shortcut) -> shortcut.toEntity(itemId) }.sortedBy { it.itemId },
                widgets = widgetItems.sortedBy { it.itemId },
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

    private fun toModel(
        entity: WorkspaceItemEntity,
        applications: Map<Long, ApplicationItemEntity>,
        shortcuts: Map<Long, ShortcutItemEntity>,
        widgets: Map<Long, WidgetItemEntity>,
    ): WorkspaceItem = WorkspaceItem(
        itemId = entity.id.toULong(),
        payload = when (entity.itemKind) {
            WorkspaceItemEntity.ITEM_FOLDER -> ItemPayload.Folder
            else -> payloadFor(entity.id, applications, shortcuts, widgets)
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
            itemKind = when (item.payload) {
                is ItemPayload.Application -> WorkspaceItemEntity.ITEM_APPLICATION
                is ItemPayload.Shortcut -> WorkspaceItemEntity.ITEM_SHORTCUT
                is ItemPayload.Widget -> WorkspaceItemEntity.ITEM_WIDGET
                is ItemPayload.Folder -> WorkspaceItemEntity.ITEM_FOLDER
            },
        )
    }

    private fun ApplicationItemEntity.toComponent() = ComponentId(packageName, className, profileId.toULong())

    private fun ComponentId.toEntity(itemId: ULong) = ApplicationItemEntity(
        itemId.toLong(),
        `package`,
        `class`,
        profileId.toLong(),
    )

    private fun ShortcutId.toEntity(itemId: ULong) = ShortcutItemEntity(
        itemId.toLong(),
        `package`,
        shortcutId,
        profileId.toLong(),
    )

    private fun payloadFor(
        itemId: Long,
        applications: Map<Long, ApplicationItemEntity>,
        shortcuts: Map<Long, ShortcutItemEntity>,
        widgets: Map<Long, WidgetItemEntity>,
    ): ItemPayload = applications[itemId]?.let { ItemPayload.Application(it.toComponent()) }
        ?: shortcuts[itemId]?.let {
            ItemPayload.Shortcut(ShortcutId(it.packageName, it.shortcutId, it.profileId.toULong()))
        }
        ?: widgets[itemId]?.let {
            ItemPayload.Widget(WidgetProviderId(it.packageName, it.className, it.profileId.toULong()))
        }
        ?: throw IllegalStateException("item payload missing")

    private fun durationBucket(nanos: Long): String = when {
        nanos < 5_000_000 -> "lt5ms"
        nanos < 20_000_000 -> "lt20ms"
        nanos < 100_000_000 -> "lt100ms"
        else -> "gte100ms"
    }
}
