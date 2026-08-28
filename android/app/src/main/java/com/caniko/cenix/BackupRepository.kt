package com.caniko.cenix

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
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
import com.caniko.cenix.uniffi.BackupImportTarget
import com.caniko.cenix.uniffi.BackupDocument
import com.caniko.cenix.uniffi.BackupExportOptions
import com.caniko.cenix.uniffi.BackupProfileRef
import com.caniko.cenix.uniffi.BackupSettings
import com.caniko.cenix.uniffi.BackupWidgetMetadata
import com.caniko.cenix.uniffi.ComponentId
import com.caniko.cenix.uniffi.ContainerRef
import com.caniko.cenix.uniffi.ItemPayload
import com.caniko.cenix.uniffi.ProfileAccess
import com.caniko.cenix.uniffi.ProfileKind
import com.caniko.cenix.uniffi.ProfileMapping
import com.caniko.cenix.uniffi.ShortcutId
import com.caniko.cenix.uniffi.WidgetProviderId
import com.caniko.cenix.uniffi.WorkspaceItem
import com.caniko.cenix.uniffi.buildBackupDocument
import com.caniko.cenix.uniffi.planBackupImport
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class BackupRepository(private val db: CenixDatabase) {
    fun buildDocument(
        profiles: List<BackupProfileRef>,
        includeWork: Boolean,
        sourceVersion: String,
        sourceCommit: String,
    ): BackupDocument {
        val launcher = LauncherRepository(db)
        val settings = checkNotNull(db.dao().launcherSettings())
        return buildBackupDocument(
            launcher.snapshot(),
            BackupSettings(settings.gridName, settings.notificationDots, settings.themedIcons, settings.autoAddApps),
            db.dao().workspaceWidgets().map {
                BackupWidgetMetadata(it.itemId.toULong(), it.minSpanX, it.minSpanY, it.resizeX, it.resizeY)
            },
            BackupExportOptions(includeWork, sourceVersion, sourceCommit, profiles),
            launcher.nextItemId(),
            launcher.nextPageId(),
        )
    }

    fun planPersonalImport(context: Context, document: BackupDocument): BackupImportPlan {
        if (document.profiles.any { it.kind != ProfileKind.PERSONAL }) throw BackupJsonException("work")
        val profiles = ProfileController(context).also { it.refresh() }
        val personal = profiles.profiles().singleOrNull {
            it.descriptor.kind == ProfileKind.PERSONAL && it.descriptor.access == ProfileAccess.AVAILABLE
        } ?: throw BackupJsonException("profile")
        val profileId = personal.descriptor.profileId
        val catalog = AppCatalog(context, profiles)
        val applications = catalog.load(listOf(personal)).map {
            ComponentId(it.packageName, it.className, profileId)
        }
        val requestedShortcuts = (
            document.workspace.items.map { it.payload } +
                document.workspace.folders.flatMap { folder -> folder.members.map { it.payload } }
            ).mapNotNull { (it as? ItemPayload.Shortcut)?.shortcut }
            .map { ShortcutId(it.`package`, it.shortcutId, profileId) }
        val shortcuts = ShortcutCatalog(context, catalog, profiles).resolve(requestedShortcuts).keys.toList()
        val widgets = try {
            context.getSystemService(AppWidgetManager::class.java).getInstalledProvidersForProfile(personal.user).mapNotNull { info ->
                val home = info.widgetCategory == 0 ||
                    info.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0
                if (home) WidgetProviderId(info.provider.packageName, info.provider.className, profileId) else null
            }
        } catch (_: RuntimeException) {
            emptyList()
        }
        val generation = LauncherRepository(db).snapshot().generation
        val metrics = context.resources.displayMetrics
        val grids = PhoneGrid.compatible(
            minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density,
            maxOf(metrics.widthPixels, metrics.heightPixels) / metrics.density,
        ).map { it.name }
        return planBackupImport(
            document,
            BackupImportTarget(
                generation,
                generation,
                listOf(BackupProfileRef(profileId, ProfileKind.PERSONAL)),
                grids,
                applications,
                shortcuts,
                widgets,
            ),
            listOf(ProfileMapping(0uL, profileId)),
        )
    }

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

    fun applyPlan(plan: BackupImportPlan, payload: String) = applyPlan(plan, payload, complete = false)

    fun applyLocalPlan(plan: BackupImportPlan, payload: String) = applyPlan(plan, payload, complete = true)

    fun recoverSystem(context: Context): Boolean {
        var operation = pending() ?: return false
        if (operation.source != RestoreSource.SYSTEM) return false
        if (operation.phase == RestorePhase.PARSED) {
            queueSystem()
            operation = checkNotNull(pending())
        }
        if (operation.phase == RestorePhase.FAILED) operation = retry() ?: return false
        if (operation.phase == RestorePhase.PLATFORM_RECONCILE) {
            return operation.committedGeneration?.let(::complete) == true
        }
        if (operation.phase != RestorePhase.SYSTEM_RESTORE_PENDING) return false
        val document = try {
            BackupJsonCodec.read(ByteArrayInputStream(operation.payload.toByteArray(Charsets.UTF_8)))
        } catch (error: Exception) {
            db.dao().recordRestoreFailure(operation.payloadSha256)
            throw error
        }
        val plan = try {
            planPersonalImport(context, document)
        } catch (error: Exception) {
            db.dao().recordRestoreFailure(operation.payloadSha256)
            throw error
        }
        applyPlan(plan, operation.payload, complete = true)
        return true
    }

    private fun applyPlan(plan: BackupImportPlan, payload: String, complete: Boolean) {
        val workspace = plan.workspace
        val payloadSha256 = sha256(payload)
        val widgetMeta = plan.widgets.associateBy { it.itemId }
        val payloads = workspace.items.map { it.itemId to it.payload } +
            workspace.folders.flatMap { folder -> folder.members.map { it.itemId to it.payload } }
        val apply = {
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
            if (complete) check(db.dao().completeRestore(workspace.generation.toLong()))
        }
        try {
            if (complete) db.runInTransaction { apply() } else apply()
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
