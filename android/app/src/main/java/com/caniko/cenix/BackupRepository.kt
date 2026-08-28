package com.caniko.cenix

import com.caniko.cenix.db.ApplicationItemEntity
import com.caniko.cenix.db.CenixDatabase
import com.caniko.cenix.db.FolderEntity
import com.caniko.cenix.db.FolderMemberEntity
import com.caniko.cenix.db.LauncherSettingsEntity
import com.caniko.cenix.db.PendingRestoreOperationEntity
import com.caniko.cenix.db.RestorePhase
import com.caniko.cenix.db.RestoreSource
import com.caniko.cenix.db.ShortcutItemEntity
import com.caniko.cenix.db.WidgetItemEntity
import com.caniko.cenix.db.WorkspaceItemEntity
import com.caniko.cenix.db.WorkspaceMetadataEntity
import com.caniko.cenix.db.WorkspacePageEntity
import com.caniko.cenix.uniffi.BackupImportPlan
import com.caniko.cenix.uniffi.ComponentId
import com.caniko.cenix.uniffi.ContainerRef
import com.caniko.cenix.uniffi.ItemPayload
import com.caniko.cenix.uniffi.ShortcutId
import com.caniko.cenix.uniffi.WorkspaceItem
import java.security.MessageDigest

class BackupRepository(private val db: CenixDatabase) {
    fun pending(): PendingRestoreOperationEntity? = db.dao().pendingRestore()

    fun confirmLocal() {
        if (db.dao().confirmLocalRestore() != 1) throw IllegalStateException("restore is not awaiting confirmation")
    }

    fun queueSystem() {
        if (db.dao().queueSystemRestore() != 1) throw IllegalStateException("system restore is not pending")
    }

    fun retry(): PendingRestoreOperationEntity? = db.dao().retryRestore()

    fun complete(committedGeneration: Long): Boolean = db.dao().completeRestore(committedGeneration)

    fun stage(source: RestoreSource, payload: String, expectedGeneration: Long, now: Long) {
        db.dao().stageRestore(
            PendingRestoreOperationEntity(
                source = source,
                phase = RestorePhase.PARSED,
                payload = payload,
                payloadSha256 = sha256(payload),
                createdAt = now,
                attemptCount = 0,
                expectedGeneration = expectedGeneration,
            ),
        )
    }

    fun applyPlan(plan: BackupImportPlan, payload: String) {
        val workspace = plan.workspace
        val payloadSha256 = sha256(payload)
        val widgetMeta = plan.widgets.associateBy { it.itemId }
        val payloads = workspace.items.map { it.itemId to it.payload } +
            workspace.folders.flatMap { folder -> folder.members.map { it.itemId to it.payload } }
        try {
            db.dao().replaceWorkspaceFromRestore(
                expectedGeneration = workspace.generation.toLong() - 1,
                payloadSha256 = payloadSha256,
                metadata = WorkspaceMetadataEntity(
                    generation = workspace.generation.toLong(),
                    cols = workspace.grid.cols,
                    rows = workspace.grid.rows,
                    hotseatCols = workspace.grid.hotseatCols,
                    nextItemId = plan.nextItemId.toLong(),
                    nextPageId = plan.nextPageId.toLong(),
                ),
                pages = workspace.pages.map { WorkspacePageEntity(it.pageId.toLong(), it.rank) },
                items = workspace.items.map(::toEntity),
                applications = payloads.mapNotNull { (itemId, payload) ->
                    (payload as? ItemPayload.Application)?.let { itemId to it.component }
                }.map { (itemId, component) -> component.toEntity(itemId) }.sortedBy { it.itemId },
                shortcuts = payloads.mapNotNull { (itemId, payload) ->
                    (payload as? ItemPayload.Shortcut)?.let { itemId to it.shortcut }
                }.map { (itemId, shortcut) -> shortcut.toEntity(itemId) }.sortedBy { it.itemId },
                widgets = payloads.mapNotNull { (itemId, payload) ->
                    (payload as? ItemPayload.Widget)?.let { itemId to it.provider }
                }.map { (itemId, provider) ->
                    val meta = checkNotNull(widgetMeta[itemId])
                    WidgetItemEntity(
                        itemId.toLong(),
                        provider.`package`,
                        provider.`class`,
                        provider.profileId.toLong(),
                        null,
                        meta.minSpanX,
                        meta.minSpanY,
                        meta.resizeX,
                        meta.resizeY,
                    )
                }.sortedBy { it.itemId },
                folders = workspace.folders.map { FolderEntity(it.folderId.toLong(), it.title) },
                folderMembers = workspace.folders.flatMap { folder ->
                    folder.members.map { FolderMemberEntity(it.itemId.toLong(), folder.folderId.toLong(), it.rank.toInt()) }
                },
                settings = LauncherSettingsEntity(
                    gridName = plan.settings.gridName,
                    notificationDots = plan.settings.notificationDots,
                    themedIcons = plan.settings.themedIcons,
                    autoAddApps = plan.settings.autoAddApps,
                ),
            )
        } catch (error: RuntimeException) {
            db.dao().recordRestoreFailure(payloadSha256)
            throw error
        }
    }

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

    private fun ComponentId.toEntity(itemId: ULong) = ApplicationItemEntity(itemId.toLong(), `package`, `class`, profileId.toLong())

    private fun ShortcutId.toEntity(itemId: ULong) = ShortcutItemEntity(itemId.toLong(), `package`, shortcutId, profileId.toLong())

    companion object {
        fun sha256(payload: String): String =
            MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
