package com.caniko.cenix.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.caniko.cenix.StartupState
import com.caniko.cenix.StartupStore
import com.caniko.cenix.PhoneGrid
import java.security.MessageDigest

private fun restoreSha256(payload: String): String = MessageDigest.getInstance("SHA-256")
    .digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

@Entity(tableName = "launcher_metadata")
data class MetadataEntity(
    @PrimaryKey val singletonId: Int = 1,
    val startupInProgress: Boolean,
    val startupFailures: Int,
    val lastFailureAt: Long,
    val emergency: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "workspace_metadata")
data class WorkspaceMetadataEntity(
    @PrimaryKey val singletonId: Int = 1,
    val generation: Long,
    val cols: Int,
    val rows: Int,
    val hotseatCols: Int,
    val nextItemId: Long = 1,
    val nextPageId: Long = 2,
)

@Entity(tableName = "launcher_settings")
data class LauncherSettingsEntity(
    @PrimaryKey val singletonId: Int = 1,
    val gridName: String,
    val notificationDots: Boolean = true,
    val themedIcons: Boolean = false,
    val autoAddApps: Boolean = true,
)

@Entity(
    tableName = "workspace_pages",
    indices = [Index(value = ["rank"], unique = true)],
)
data class WorkspacePageEntity(
    @PrimaryKey val pageId: Long,
    val rank: Int,
)

@Entity(
    tableName = "workspace_items",
    indices = [Index(value = ["containerKind", "containerId", "cellX", "cellY"], unique = true)],
)
data class WorkspaceItemEntity(
    @PrimaryKey val id: Long,
    val containerKind: String,
    val containerId: Long,
    val cellX: Int,
    val cellY: Int,
    val spanX: Int = 1,
    val spanY: Int = 1,
    val itemKind: String = ITEM_APPLICATION,
) {
    companion object {
        const val CONTAINER_WORKSPACE = "WORKSPACE"
        const val CONTAINER_HOTSEAT = "HOTSEAT"
        const val ITEM_APPLICATION = "APPLICATION"
        const val ITEM_FOLDER = "FOLDER"
        const val ITEM_SHORTCUT = "SHORTCUT"
        const val ITEM_WIDGET = "WIDGET"
    }
}

@Entity(
    tableName = "workspace_applications",
    indices = [Index(value = ["packageName", "className", "profileId"], unique = true)],
)
data class ApplicationItemEntity(
    @PrimaryKey val itemId: Long,
    val packageName: String,
    val className: String,
    val profileId: Long,
)

@Entity(
    tableName = "workspace_shortcuts",
    indices = [Index(value = ["packageName", "shortcutId", "profileId"], unique = true)],
)
data class ShortcutItemEntity(
    @PrimaryKey val itemId: Long,
    val packageName: String,
    val shortcutId: String,
    val profileId: Long,
)

@Entity(
    tableName = "workspace_widgets",
    indices = [Index(value = ["appWidgetId"], unique = true)],
)
data class WidgetItemEntity(
    @PrimaryKey val itemId: Long,
    val packageName: String,
    val className: String,
    val profileId: Long,
    val appWidgetId: Int?,
    val minSpanX: Int = 1,
    val minSpanY: Int = 1,
    val resizeX: Int = 1,
    val resizeY: Int = 1,
)

enum class WidgetOperationKind { ADD, PIN, RESTORE }

enum class WidgetOperationPhase {
    ALLOCATING,
    ALLOCATED,
    BIND_PERMISSION_PENDING,
    BOUND,
    CONFIGURATION_PENDING,
    PLATFORM_ACCEPTED,
    COMMITTING,
    ROLLING_BACK,
    RESTORE_PENDING,
    REMAP_PENDING,
}

enum class RestoreSource { LOCAL, SYSTEM }

enum class RestorePhase {
    PARSED,
    USER_CONFIRMED,
    SYSTEM_RESTORE_PENDING,
    APPLYING,
    PLATFORM_RECONCILE,
    FAILED,
}

class WidgetOperationConverters {
    @TypeConverter fun kind(value: WidgetOperationKind): String = value.name
    @TypeConverter fun kind(value: String): WidgetOperationKind = WidgetOperationKind.valueOf(value)
    @TypeConverter fun phase(value: WidgetOperationPhase): String = value.name
    @TypeConverter fun phase(value: String): WidgetOperationPhase = WidgetOperationPhase.valueOf(value)
}

class RestoreOperationConverters {
    @TypeConverter fun source(value: RestoreSource): String = value.name
    @TypeConverter fun source(value: String): RestoreSource = RestoreSource.valueOf(value)
    @TypeConverter fun restorePhase(value: RestorePhase): String = value.name
    @TypeConverter fun restorePhase(value: String): RestorePhase = RestorePhase.valueOf(value)
}

@Entity(
    tableName = "pending_widget_operations",
    indices = [Index(value = ["appWidgetId"], unique = true)],
)
data class PendingWidgetOperationEntity(
    @PrimaryKey val itemId: Long,
    val kind: WidgetOperationKind,
    val phase: WidgetOperationPhase,
    val appWidgetId: Int?,
    val replacementAppWidgetId: Int?,
    val packageName: String,
    val className: String,
    val profileId: Long,
    val pageId: Long,
    val cellX: Int,
    val cellY: Int,
    val spanX: Int,
    val spanY: Int,
    val updatedAt: Long,
)

@Entity(tableName = "pending_restore_operations")
data class PendingRestoreOperationEntity(
    @PrimaryKey val singletonId: Int = 1,
    val source: RestoreSource,
    val phase: RestorePhase,
    val payload: String,
    val payloadSha256: String,
    val createdAt: Long,
    val attemptCount: Int,
    val expectedGeneration: Long,
    val committedGeneration: Long? = null,
) {
    companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_PAYLOAD_BYTES = 1024 * 1024
    }
}

@Entity(tableName = "workspace_folders")
data class FolderEntity(
    @PrimaryKey val folderId: Long,
    val title: String,
)

@Entity(
    tableName = "folder_members",
    indices = [Index(value = ["folderId", "rank"], unique = true)],
)
data class FolderMemberEntity(
    @PrimaryKey val itemId: Long,
    val folderId: Long,
    val rank: Int,
)

data class WorkspaceRows(
    val metadata: WorkspaceMetadataEntity,
    val pages: List<WorkspacePageEntity>,
    val items: List<WorkspaceItemEntity>,
    val applications: List<ApplicationItemEntity>,
    val shortcuts: List<ShortcutItemEntity>,
    val widgets: List<WidgetItemEntity>,
    val folders: List<FolderEntity>,
    val folderMembers: List<FolderMemberEntity>,
)

class StaleWorkspaceGeneration : IllegalStateException("stale workspace generation")
class InvalidWorkspaceTransition : IllegalStateException("invalid workspace transition")

@Dao
interface CenixDao {
    @Query("SELECT * FROM launcher_metadata WHERE singletonId = 1")
    fun metadata(): MetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertMetadata(entity: MetadataEntity)

    @Query("SELECT * FROM workspace_metadata WHERE singletonId = 1")
    fun workspaceMetadata(): WorkspaceMetadataEntity?

    @Query("SELECT * FROM launcher_settings WHERE singletonId = 1")
    fun launcherSettings(): LauncherSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertLauncherSettings(entity: LauncherSettingsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertWorkspaceMetadata(entity: WorkspaceMetadataEntity)

    @Query("SELECT * FROM workspace_pages ORDER BY rank")
    fun workspacePages(): List<WorkspacePageEntity>

    @Query("SELECT * FROM workspace_items ORDER BY CASE containerKind WHEN 'WORKSPACE' THEN 0 ELSE 1 END, containerId, cellY, cellX, id")
    fun workspaceItems(): List<WorkspaceItemEntity>

    @Query("SELECT * FROM workspace_applications ORDER BY itemId")
    fun workspaceApplications(): List<ApplicationItemEntity>

    @Query("SELECT * FROM workspace_shortcuts ORDER BY itemId")
    fun workspaceShortcuts(): List<ShortcutItemEntity>

    @Query("SELECT * FROM workspace_widgets ORDER BY itemId")
    fun workspaceWidgets(): List<WidgetItemEntity>

    @Query("SELECT * FROM pending_widget_operations ORDER BY itemId")
    fun pendingWidgetOperations(): List<PendingWidgetOperationEntity>

    @Query("SELECT * FROM pending_restore_operations WHERE singletonId = 1")
    fun pendingRestore(): PendingRestoreOperationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertPendingRestore(entity: PendingRestoreOperationEntity)

    @Query("DELETE FROM pending_restore_operations")
    fun deletePendingRestore()

    @Query("UPDATE pending_restore_operations SET phase = 'USER_CONFIRMED' WHERE singletonId = 1 AND source = 'LOCAL' AND phase = 'PARSED'")
    fun confirmLocalRestore(): Int

    @Query("UPDATE pending_restore_operations SET phase = 'SYSTEM_RESTORE_PENDING' WHERE singletonId = 1 AND source = 'SYSTEM' AND phase = 'PARSED'")
    fun queueSystemRestore(): Int

    @Query(
        "UPDATE pending_restore_operations SET phase = 'APPLYING' WHERE singletonId = 1 AND payloadSha256 = :payloadSha256 " +
            "AND ((source = 'LOCAL' AND phase = 'USER_CONFIRMED') OR (source = 'SYSTEM' AND phase = 'SYSTEM_RESTORE_PENDING'))",
    )
    fun markRestoreApplying(payloadSha256: String): Int

    @Query(
        "UPDATE pending_restore_operations SET phase = 'FAILED', attemptCount = attemptCount + 1 " +
            "WHERE singletonId = 1 AND payloadSha256 = :payloadSha256 " +
            "AND phase IN ('USER_CONFIRMED', 'SYSTEM_RESTORE_PENDING')",
    )
    fun recordRestoreFailure(payloadSha256: String): Int

    @Query("DELETE FROM pending_restore_operations WHERE createdAt < :cutoff AND phase IN ('PARSED', 'USER_CONFIRMED', 'SYSTEM_RESTORE_PENDING', 'FAILED')")
    fun cleanupStaleRestores(cutoff: Long): Int

    @Query("SELECT * FROM workspace_folders ORDER BY folderId")
    fun workspaceFolders(): List<FolderEntity>

    @Query("SELECT * FROM folder_members ORDER BY folderId, rank")
    fun folderMembers(): List<FolderMemberEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertPages(entities: List<WorkspacePageEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertWorkspace(entities: List<WorkspaceItemEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertApplications(entities: List<ApplicationItemEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertShortcuts(entities: List<ShortcutItemEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertWidgets(entities: List<WidgetItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertPendingWidgetOperation(entity: PendingWidgetOperationEntity)

    @Query("DELETE FROM pending_widget_operations WHERE itemId = :itemId")
    fun deletePendingWidgetOperation(itemId: Long)

    @Query("DELETE FROM pending_widget_operations")
    fun clearPendingWidgetOperations()

    @Query("UPDATE workspace_widgets SET appWidgetId = :appWidgetId WHERE itemId = :itemId")
    fun updateWidgetBinding(itemId: Long, appWidgetId: Int?)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertFolders(entities: List<FolderEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertFolderMembers(entities: List<FolderMemberEntity>)

    @Query("DELETE FROM workspace_pages")
    fun clearPages()

    @Query("DELETE FROM workspace_items")
    fun clearWorkspace()

    @Query("DELETE FROM workspace_applications")
    fun clearApplications()

    @Query("DELETE FROM workspace_shortcuts")
    fun clearShortcuts()

    @Query("DELETE FROM workspace_widgets")
    fun clearWidgets()

    @Query("DELETE FROM workspace_folders")
    fun clearFolders()

    @Query("DELETE FROM folder_members")
    fun clearFolderMembers()

    @Transaction
    fun remapWidgetIds(oldIds: IntArray, newIds: IntArray, now: Long): IntArray {
        if (oldIds.size != newIds.size) throw InvalidWorkspaceTransition()
        val widgets = workspaceWidgets().associateBy { it.appWidgetId }
        val items = workspaceItems().associateBy { it.id }
        val mappings = oldIds.indices.mapNotNull { index ->
            widgets[oldIds[index]]?.let { Triple(it, items[it.itemId] ?: throw InvalidWorkspaceTransition(), newIds[index]) }
        }
        mappings.forEach { (widget, item, replacement) ->
            upsertPendingWidgetOperation(
                PendingWidgetOperationEntity(
                    widget.itemId, WidgetOperationKind.RESTORE, WidgetOperationPhase.RESTORE_PENDING,
                    widget.appWidgetId, replacement, widget.packageName, widget.className, widget.profileId,
                    item.containerId, item.cellX, item.cellY, item.spanX, item.spanY, now,
                ),
            )
        }
        mappings.forEach { (widget) -> updateWidgetBinding(widget.itemId, null) }
        mappings.forEach { (widget, item, replacement) ->
            upsertPendingWidgetOperation(
                PendingWidgetOperationEntity(
                    widget.itemId, WidgetOperationKind.RESTORE, WidgetOperationPhase.REMAP_PENDING,
                    widget.appWidgetId, replacement, widget.packageName, widget.className, widget.profileId,
                    item.containerId, item.cellX, item.cellY, item.spanX, item.spanY, now,
                ),
            )
            updateWidgetBinding(widget.itemId, replacement)
            deletePendingWidgetOperation(widget.itemId)
        }
        return mappings.map { it.third }.toIntArray()
    }

    @Query(
        "UPDATE workspace_metadata SET generation = :nextGeneration, cols = :cols, rows = :rows, hotseatCols = :hotseatCols, " +
            "nextItemId = :nextItemId, nextPageId = :nextPageId " +
            "WHERE singletonId = 1 AND generation = :expectedGeneration",
    )
    fun advanceGeneration(
        expectedGeneration: Long,
        nextGeneration: Long,
        cols: Int,
        rows: Int,
        hotseatCols: Int,
        nextItemId: Long,
        nextPageId: Long,
    ): Int

    @Transaction
    fun workspaceState(): WorkspaceRows = WorkspaceRows(
        metadata = checkNotNull(workspaceMetadata()),
        pages = workspacePages(),
        items = workspaceItems(),
        applications = workspaceApplications(),
        shortcuts = workspaceShortcuts(),
        widgets = workspaceWidgets(),
        folders = workspaceFolders(),
        folderMembers = folderMembers(),
    )

    @Transaction
    fun commitWorkspace(
        expectedGeneration: Long,
        metadata: WorkspaceMetadataEntity,
        pages: List<WorkspacePageEntity>,
        items: List<WorkspaceItemEntity>,
        applications: List<ApplicationItemEntity> = emptyList(),
        shortcuts: List<ShortcutItemEntity> = emptyList(),
        widgets: List<WidgetItemEntity> = emptyList(),
        folders: List<FolderEntity> = emptyList(),
        folderMembers: List<FolderMemberEntity> = emptyList(),
    ) {
        validateWorkspaceRows(items, applications, shortcuts, widgets, folders, folderMembers)
        val currentMetadata = checkNotNull(workspaceMetadata())
        if (currentMetadata.generation != expectedGeneration) throw StaleWorkspaceGeneration()
        if (metadata.generation == expectedGeneration) {
            if (
                metadata != currentMetadata ||
                pages != workspacePages() ||
                items != workspaceItems() ||
                applications != workspaceApplications() ||
                shortcuts != workspaceShortcuts() ||
                widgets != workspaceWidgets() ||
                folders != workspaceFolders() ||
                folderMembers != this.folderMembers()
            ) {
                throw InvalidWorkspaceTransition()
            }
            return
        }
        if (metadata.generation != expectedGeneration + 1) throw InvalidWorkspaceTransition()
        if (
            advanceGeneration(
                expectedGeneration,
                metadata.generation,
                metadata.cols,
                metadata.rows,
                metadata.hotseatCols,
                metadata.nextItemId,
                metadata.nextPageId,
            ) != 1
        ) {
            throw StaleWorkspaceGeneration()
        }
        clearFolderMembers()
        clearFolders()
        clearWidgets()
        clearShortcuts()
        clearApplications()
        clearWorkspace()
        clearPages()
        insertPages(pages)
        if (items.isNotEmpty()) insertWorkspace(items)
        if (applications.isNotEmpty()) insertApplications(applications)
        if (shortcuts.isNotEmpty()) insertShortcuts(shortcuts)
        if (widgets.isNotEmpty()) insertWidgets(widgets)
        if (folders.isNotEmpty()) insertFolders(folders)
        if (folderMembers.isNotEmpty()) insertFolderMembers(folderMembers)
    }

    @Transaction
    fun stageRestore(entity: PendingRestoreOperationEntity) {
        if (
            entity.singletonId != 1 || entity.phase != RestorePhase.PARSED || entity.attemptCount != 0 ||
            entity.committedGeneration != null || entity.expectedGeneration < 0 || entity.createdAt < 0 ||
            entity.payload.toByteArray(Charsets.UTF_8).size > PendingRestoreOperationEntity.MAX_PAYLOAD_BYTES ||
            entity.payloadSha256 != restoreSha256(entity.payload)
        ) {
            throw InvalidWorkspaceTransition()
        }
        val existing = pendingRestore()
        if (existing != null) {
            if (
                existing.payloadSha256 == entity.payloadSha256 && existing.source == entity.source &&
                existing.expectedGeneration == entity.expectedGeneration && existing.phase != RestorePhase.FAILED
            ) {
                return
            }
            if (existing.phase !in setOf(RestorePhase.PARSED, RestorePhase.FAILED) && !restoreReconcileIsStale(existing)) {
                throw InvalidWorkspaceTransition()
            }
        }
        upsertPendingRestore(entity.copy(singletonId = 1))
    }

    @Transaction
    fun retryRestore(maxAttempts: Int = PendingRestoreOperationEntity.MAX_ATTEMPTS): PendingRestoreOperationEntity? {
        val current = pendingRestore() ?: return null
        if (current.phase != RestorePhase.FAILED || current.attemptCount >= maxAttempts) return current
        upsertPendingRestore(
            current.copy(
                phase = if (current.source == RestoreSource.LOCAL) RestorePhase.PARSED else RestorePhase.SYSTEM_RESTORE_PENDING,
            ),
        )
        return pendingRestore()
    }

    @Query("UPDATE pending_restore_operations SET phase = 'PLATFORM_RECONCILE', committedGeneration = :committedGeneration WHERE singletonId = 1 AND phase = 'APPLYING'")
    fun markRestoreReconcile(committedGeneration: Long): Int

    @Transaction
    fun completeRestore(committedGeneration: Long): Boolean {
        val current = pendingRestore() ?: return false
        if (current.phase != RestorePhase.PLATFORM_RECONCILE || current.committedGeneration != committedGeneration) return false
        deletePendingRestore()
        return true
    }

    @Transaction
    fun replaceWorkspaceFromRestore(
        expectedGeneration: Long,
        payloadSha256: String,
        metadata: WorkspaceMetadataEntity,
        pages: List<WorkspacePageEntity>,
        items: List<WorkspaceItemEntity>,
        applications: List<ApplicationItemEntity> = emptyList(),
        shortcuts: List<ShortcutItemEntity> = emptyList(),
        widgets: List<WidgetItemEntity> = emptyList(),
        folders: List<FolderEntity> = emptyList(),
        folderMembers: List<FolderMemberEntity> = emptyList(),
        settings: LauncherSettingsEntity,
    ) {
        val journal = pendingRestore() ?: throw InvalidWorkspaceTransition()
        val currentMetadata = checkNotNull(workspaceMetadata())
        if (journal.payloadSha256 != payloadSha256) throw InvalidWorkspaceTransition()
        if (
            journal.phase == RestorePhase.PLATFORM_RECONCILE &&
            journal.committedGeneration == currentMetadata.generation &&
            currentMetadata.generation == expectedGeneration + 1
        ) {
            return
        }
        if (currentMetadata.generation != expectedGeneration || journal.expectedGeneration != expectedGeneration) {
            throw StaleWorkspaceGeneration()
        }
        if (metadata.generation != expectedGeneration + 1) throw InvalidWorkspaceTransition()
        if (markRestoreApplying(payloadSha256) != 1) {
            throw InvalidWorkspaceTransition()
        }
        val unbound = widgets.map { it.copy(appWidgetId = null) }
        validateWorkspaceRows(items, applications, shortcuts, unbound, folders, folderMembers)
        if (
            advanceGeneration(
                expectedGeneration,
                metadata.generation,
                metadata.cols,
                metadata.rows,
                metadata.hotseatCols,
                metadata.nextItemId,
                metadata.nextPageId,
            ) != 1
        ) {
            throw StaleWorkspaceGeneration()
        }
        clearPendingWidgetOperations()
        clearFolderMembers()
        clearFolders()
        clearWidgets()
        clearShortcuts()
        clearApplications()
        clearWorkspace()
        clearPages()
        insertPages(pages)
        if (items.isNotEmpty()) insertWorkspace(items)
        if (applications.isNotEmpty()) insertApplications(applications)
        if (shortcuts.isNotEmpty()) insertShortcuts(shortcuts)
        if (unbound.isNotEmpty()) insertWidgets(unbound)
        if (folders.isNotEmpty()) insertFolders(folders)
        if (folderMembers.isNotEmpty()) insertFolderMembers(folderMembers)
        upsertLauncherSettings(settings)
        if (markRestoreReconcile(metadata.generation) != 1) throw InvalidWorkspaceTransition()
    }

    private fun restoreReconcileIsStale(entity: PendingRestoreOperationEntity): Boolean =
        entity.phase == RestorePhase.PLATFORM_RECONCILE && entity.committedGeneration != workspaceMetadata()?.generation

    private fun validateWorkspaceRows(
        items: List<WorkspaceItemEntity>,
        applications: List<ApplicationItemEntity>,
        shortcuts: List<ShortcutItemEntity>,
        widgets: List<WidgetItemEntity>,
        folders: List<FolderEntity>,
        folderMembers: List<FolderMemberEntity>,
    ) {
        val itemIds = items.map { it.id }.toSet()
        val memberIds = folderMembers.map { it.itemId }.toSet()
        val applicationIds = applications.map { it.itemId }.toSet()
        val shortcutIds = shortcuts.map { it.itemId }.toSet()
        val widgetIds = widgets.map { it.itemId }.toSet()
        val folderIds = folders.map { it.folderId }.toSet()
        val placedFolderIds = items.filter { it.itemKind == WorkspaceItemEntity.ITEM_FOLDER }.map { it.id }.toSet()
        val placedPayloadIds = items.filter { it.itemKind != WorkspaceItemEntity.ITEM_FOLDER }.map { it.id }.toSet()
        if (
            itemIds.size != items.size || memberIds.size != folderMembers.size ||
            itemIds.intersect(memberIds).isNotEmpty() ||
            applicationIds.intersect(shortcutIds + widgetIds).isNotEmpty() ||
            shortcutIds.intersect(widgetIds).isNotEmpty() ||
            widgetIds.intersect(memberIds).isNotEmpty() ||
            applicationIds + shortcutIds + widgetIds != placedPayloadIds + memberIds ||
            folderIds != placedFolderIds ||
            folderMembers.any { it.folderId !in folderIds } ||
            folders.any { folder -> folderMembers.count { it.folderId == folder.folderId } < 2 } ||
            folders.any { folder ->
                folderMembers.filter { it.folderId == folder.folderId }.sortedBy { it.rank }
                    .map { it.rank } != (0 until folderMembers.count { it.folderId == folder.folderId }).toList()
            } ||
            items.any {
                it.itemKind !in setOf(
                    WorkspaceItemEntity.ITEM_APPLICATION,
                    WorkspaceItemEntity.ITEM_FOLDER,
                    WorkspaceItemEntity.ITEM_SHORTCUT,
                    WorkspaceItemEntity.ITEM_WIDGET,
                ) ||
                (it.itemKind == WorkspaceItemEntity.ITEM_APPLICATION) != (it.id in applicationIds) ||
                    (it.itemKind == WorkspaceItemEntity.ITEM_SHORTCUT) != (it.id in shortcutIds) ||
                    (it.itemKind == WorkspaceItemEntity.ITEM_WIDGET) != (it.id in widgetIds)
            } ||
            items.any {
                it.itemKind == WorkspaceItemEntity.ITEM_WIDGET &&
                    (it.containerKind == WorkspaceItemEntity.CONTAINER_HOTSEAT || widgets.first { widget -> widget.itemId == it.id }.let { widget ->
                        widget.minSpanX <= 0 || widget.minSpanY <= 0 ||
                            widget.resizeX < widget.minSpanX || widget.resizeY < widget.minSpanY ||
                            widget.minSpanX > it.spanX || widget.minSpanY > it.spanY
                    })
            }
        ) {
            throw InvalidWorkspaceTransition()
        }
    }

    @Transaction
    fun saveStartup(state: StartupState, now: Long) {
        upsertMetadata(
            MetadataEntity(
                startupInProgress = state.inProgress,
                startupFailures = state.failures,
                lastFailureAt = state.lastFailureAt,
                emergency = state.emergency,
                updatedAt = now,
            ),
        )
    }
}

class RoomStartupStore(private val db: CenixDatabase) : StartupStore {
    override fun load(): StartupState {
        val metadata = db.dao().metadata() ?: return StartupState()
        return StartupState(
            inProgress = metadata.startupInProgress,
            failures = metadata.startupFailures,
            lastFailureAt = metadata.lastFailureAt,
            emergency = metadata.emergency,
        )
    }

    override fun save(state: StartupState) {
        db.dao().saveStartup(state, System.currentTimeMillis())
    }
}

@Database(
    entities = [
        MetadataEntity::class,
        WorkspaceMetadataEntity::class,
        LauncherSettingsEntity::class,
        WorkspacePageEntity::class,
        WorkspaceItemEntity::class,
        ApplicationItemEntity::class,
        ShortcutItemEntity::class,
        WidgetItemEntity::class,
        PendingWidgetOperationEntity::class,
        PendingRestoreOperationEntity::class,
        FolderEntity::class,
        FolderMemberEntity::class,
    ],
    version = CenixDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(WidgetOperationConverters::class, RestoreOperationConverters::class)
abstract class CenixDatabase : RoomDatabase() {
    abstract fun dao(): CenixDao

    companion object {
        const val NAME = "cenix.db"
        const val VERSION = 9

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workspace_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `screen` INTEGER NOT NULL, `cellX` INTEGER NOT NULL, `cellY` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `className` TEXT NOT NULL, `profileId` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_items_screen_cellX_cellY` ON `workspace_items` (`screen`, `cellX`, `cellY`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_items_packageName_className_profileId` ON `workspace_items` (`packageName`, `className`, `profileId`)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `workspace_items` RENAME TO `workspace_items_v2`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workspace_metadata` (`singletonId` INTEGER NOT NULL, `generation` INTEGER NOT NULL, `cols` INTEGER NOT NULL, `rows` INTEGER NOT NULL, `hotseatCols` INTEGER NOT NULL, `nextItemId` INTEGER NOT NULL, `nextPageId` INTEGER NOT NULL, PRIMARY KEY(`singletonId`))",
                )
                db.execSQL(
                    "INSERT INTO `workspace_metadata` SELECT 1, 0, 4, 5, 4, COALESCE(MAX(`id`) + 1, 1), CASE WHEN EXISTS(SELECT 1 FROM `workspace_items_v2` WHERE `screen` = 1) THEN 3 ELSE 2 END FROM `workspace_items_v2`",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workspace_pages` (`pageId` INTEGER NOT NULL, `rank` INTEGER NOT NULL, PRIMARY KEY(`pageId`))",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_pages_rank` ON `workspace_pages` (`rank`)")
                db.execSQL("INSERT INTO `workspace_pages` VALUES (1, 0)")
                db.execSQL("INSERT INTO `workspace_pages` SELECT 2, 1 WHERE EXISTS(SELECT 1 FROM `workspace_items_v2` WHERE `screen` = 1)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workspace_items` (`id` INTEGER NOT NULL, `containerKind` TEXT NOT NULL, `containerId` INTEGER NOT NULL, `cellX` INTEGER NOT NULL, `cellY` INTEGER NOT NULL, `spanX` INTEGER NOT NULL, `spanY` INTEGER NOT NULL, `itemKind` TEXT NOT NULL, `packageName` TEXT NOT NULL, `className` TEXT NOT NULL, `profileId` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "INSERT INTO `workspace_items` SELECT `id`, CASE WHEN `screen` = -1 THEN 'HOTSEAT' ELSE 'WORKSPACE' END, CASE WHEN `screen` = -1 THEN 0 ELSE `screen` + 1 END, `cellX`, `cellY`, 1, 1, 'APPLICATION', `packageName`, `className`, `profileId` FROM `workspace_items_v2` WHERE `screen` IN (-1, 0, 1)",
                )
                db.execSQL("DROP TABLE `workspace_items_v2`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_items_containerKind_containerId_cellX_cellY` ON `workspace_items` (`containerKind`, `containerId`, `cellX`, `cellY`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_items_packageName_className_profileId` ON `workspace_items` (`packageName`, `className`, `profileId`)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `workspace_items` RENAME TO `workspace_items_v3`")
                db.execSQL("DROP INDEX IF EXISTS `index_workspace_items_containerKind_containerId_cellX_cellY`")
                db.execSQL("DROP INDEX IF EXISTS `index_workspace_items_packageName_className_profileId`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workspace_items` (`id` INTEGER NOT NULL, `containerKind` TEXT NOT NULL, `containerId` INTEGER NOT NULL, `cellX` INTEGER NOT NULL, `cellY` INTEGER NOT NULL, `spanX` INTEGER NOT NULL, `spanY` INTEGER NOT NULL, `itemKind` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "INSERT INTO `workspace_items` SELECT `id`, `containerKind`, `containerId`, `cellX`, `cellY`, `spanX`, `spanY`, `itemKind` FROM `workspace_items_v3`",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_items_containerKind_containerId_cellX_cellY` ON `workspace_items` (`containerKind`, `containerId`, `cellX`, `cellY`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workspace_applications` (`itemId` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `className` TEXT NOT NULL, `profileId` INTEGER NOT NULL, PRIMARY KEY(`itemId`))",
                )
                db.execSQL(
                    "INSERT INTO `workspace_applications` SELECT `id`, `packageName`, `className`, `profileId` FROM `workspace_items_v3` WHERE `itemKind` = 'APPLICATION'",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_applications_packageName_className_profileId` ON `workspace_applications` (`packageName`, `className`, `profileId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workspace_folders` (`folderId` INTEGER NOT NULL, `title` TEXT NOT NULL, PRIMARY KEY(`folderId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folder_members` (`itemId` INTEGER NOT NULL, `folderId` INTEGER NOT NULL, `rank` INTEGER NOT NULL, PRIMARY KEY(`itemId`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_folder_members_folderId_rank` ON `folder_members` (`folderId`, `rank`)",
                )
                db.execSQL("DROP TABLE `workspace_items_v3`")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workspace_shortcuts` (`itemId` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `shortcutId` TEXT NOT NULL, `profileId` INTEGER NOT NULL, PRIMARY KEY(`itemId`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_shortcuts_packageName_shortcutId_profileId` ON `workspace_shortcuts` (`packageName`, `shortcutId`, `profileId`)",
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workspace_widgets` (`itemId` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `className` TEXT NOT NULL, `profileId` INTEGER NOT NULL, `appWidgetId` INTEGER, PRIMARY KEY(`itemId`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_widgets_appWidgetId` ON `workspace_widgets` (`appWidgetId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_widget_operations` (`itemId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `phase` TEXT NOT NULL, `appWidgetId` INTEGER, `replacementAppWidgetId` INTEGER, `packageName` TEXT NOT NULL, `className` TEXT NOT NULL, `profileId` INTEGER NOT NULL, `pageId` INTEGER NOT NULL, `cellX` INTEGER NOT NULL, `cellY` INTEGER NOT NULL, `spanX` INTEGER NOT NULL, `spanY` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`itemId`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_widget_operations_appWidgetId` ON `pending_widget_operations` (`appWidgetId`)",
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `launcher_settings` (`singletonId` INTEGER NOT NULL, `gridName` TEXT NOT NULL, PRIMARY KEY(`singletonId`))",
                )
                db.execSQL(
                    "INSERT INTO `launcher_settings` SELECT 1, CASE " +
                        "WHEN `cols` = 2 AND `rows` = 2 THEN '2_by_2' " +
                        "WHEN `cols` = 3 AND `rows` = 3 THEN '3_by_3' " +
                        "WHEN `cols` = 4 AND `rows` = 4 THEN '4_by_4' " +
                        "WHEN `cols` = 4 AND `rows` = 5 THEN '4_by_5' " +
                        "WHEN `cols` = 5 AND `rows` = 5 THEN '5_by_5' ELSE '' END " +
                        "FROM `workspace_metadata` WHERE `singletonId` = 1",
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `launcher_settings` ADD COLUMN `notificationDots` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `launcher_settings` ADD COLUMN `themedIcons` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `launcher_settings` ADD COLUMN `autoAddApps` INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `workspace_widgets` ADD COLUMN `minSpanX` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `workspace_widgets` ADD COLUMN `minSpanY` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `workspace_widgets` ADD COLUMN `resizeX` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `workspace_widgets` ADD COLUMN `resizeY` INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "UPDATE `workspace_widgets` SET " +
                        "`resizeX` = COALESCE((SELECT `spanX` FROM `workspace_items` WHERE `workspace_items`.`id` = `workspace_widgets`.`itemId`), `resizeX`), " +
                        "`resizeY` = COALESCE((SELECT `spanY` FROM `workspace_items` WHERE `workspace_items`.`id` = `workspace_widgets`.`itemId`), `resizeY`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_restore_operations` (`singletonId` INTEGER NOT NULL, `source` TEXT NOT NULL, `phase` TEXT NOT NULL, `payload` TEXT NOT NULL, `payloadSha256` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `attemptCount` INTEGER NOT NULL, `expectedGeneration` INTEGER NOT NULL, `committedGeneration` INTEGER, PRIMARY KEY(`singletonId`))",
                )
            }
        }

        fun open(context: Context): CenixDatabase {
            val builder = Room.databaseBuilder(context.applicationContext, CenixDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            if (android.os.Build.FINGERPRINT == "robolectric") builder.allowMainThreadQueries()
            val metrics = context.resources.displayMetrics
            val grid = PhoneGrid.pick(
                minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density,
                maxOf(metrics.widthPixels, metrics.heightPixels) / metrics.density,
            )
            return builder.build().also { it.ensureSeed(grid) }
        }
    }

    @Transaction
    fun ensureSeed(defaultGrid: PhoneGrid = PhoneGrid.DEFAULT) {
        if (dao().metadata() == null) {
            dao().upsertMetadata(
                MetadataEntity(
                    startupInProgress = false,
                    startupFailures = 0,
                    lastFailureAt = 0,
                    emergency = false,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        if (dao().workspaceMetadata() == null) {
            dao().upsertWorkspaceMetadata(
                WorkspaceMetadataEntity(
                    generation = 0,
                    cols = defaultGrid.cols,
                    rows = defaultGrid.rows,
                    hotseatCols = defaultGrid.cols,
                ),
            )
            dao().insertPages(listOf(WorkspacePageEntity(pageId = 1, rank = 0)))
        }
        if (dao().launcherSettings() == null) {
            dao().upsertLauncherSettings(LauncherSettingsEntity(gridName = defaultGrid.name))
        }
    }
}
