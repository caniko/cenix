package com.caniko.cenix.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.caniko.cenix.BackupRepository
import com.caniko.cenix.CrashLoopGuard
import com.caniko.cenix.StartupState
import com.caniko.cenix.uniffi.BackupImportPlan
import com.caniko.cenix.uniffi.BackupSettings
import com.caniko.cenix.uniffi.BackupWidgetMetadata
import com.caniko.cenix.uniffi.CellRect
import com.caniko.cenix.uniffi.ContainerRef
import com.caniko.cenix.uniffi.GridSpec
import com.caniko.cenix.uniffi.ItemPayload
import com.caniko.cenix.uniffi.WidgetProviderId
import com.caniko.cenix.uniffi.WorkspaceItem
import com.caniko.cenix.uniffi.WorkspacePage
import com.caniko.cenix.uniffi.WorkspaceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CenixDatabaseTest {
    @Test
    fun startupStateRoundTrip() {
        val db = openDb()
        val store = RoomStartupStore(db)
        store.save(StartupState(inProgress = true, failures = 2, lastFailureAt = 9, emergency = false))
        val loaded = store.load()
        assertEquals(2, loaded.failures)
        assertFalse(loaded.emergency)
        assertNotNull(db.dao().metadata())
        db.close()
    }

    @Test
    fun crashLoopPersistsAcrossNewGuard() {
        val db = openDb()
        val store = RoomStartupStore(db)
        var now = 1_000L
        val first = CrashLoopGuard(store, clock = { now })
        assertTrue(first.beginStartup())
        now += 1
        assertTrue(first.beginStartup())
        now += 1
        assertTrue(first.beginStartup())
        now += 1
        assertFalse(first.beginStartup())
        val second = CrashLoopGuard(store, clock = { now })
        assertFalse(second.isEmergency())
        assertFalse(second.beginStartup())
        now += 61_000
        assertTrue(second.beginStartup())
        db.close()
    }

    @Test
    fun seedCreatesMetadata() {
        val db = openDb()
        assertNotNull(db.dao().metadata())
        db.close()
    }

    @Test
    fun widgetJournalIsTypedAndIndependentFromWorkspaceCommits() {
        val db = openDb()
        val operation = PendingWidgetOperationEntity(
            itemId = 9,
            kind = WidgetOperationKind.PIN,
            phase = WidgetOperationPhase.BIND_PERMISSION_PENDING,
            appWidgetId = 42,
            replacementAppWidgetId = null,
            packageName = "widgets",
            className = "Clock",
            profileId = 0,
            pageId = 1,
            cellX = 0,
            cellY = 0,
            spanX = 2,
            spanY = 1,
            updatedAt = 10,
        )
        db.dao().upsertPendingWidgetOperation(operation)
        db.dao().commitWorkspace(
            expectedGeneration = 0,
            metadata = WorkspaceMetadataEntity(generation = 1, cols = 4, rows = 5, hotseatCols = 4),
            pages = listOf(WorkspacePageEntity(1, 0)),
            items = emptyList(),
        )
        assertEquals(operation, db.dao().pendingWidgetOperations().single())
        db.dao().deletePendingWidgetOperation(9)
        assertTrue(db.dao().pendingWidgetOperations().isEmpty())
        db.close()
    }

    @Test
    fun migratesV1ToV8KeepsMetadata() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "cenix-migrate.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val sqlite = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        sqlite.execSQL(
            "CREATE TABLE IF NOT EXISTS `launcher_metadata` (`singletonId` INTEGER NOT NULL, `startupInProgress` INTEGER NOT NULL, `startupFailures` INTEGER NOT NULL, `lastFailureAt` INTEGER NOT NULL, `emergency` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`singletonId`))",
        )
        sqlite.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        sqlite.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '4245c49e2ef1c5f9cb6826b6784234ad')")
        sqlite.execSQL(
            "INSERT INTO launcher_metadata VALUES (1, 0, 2, 9, 1, 100)",
        )
        sqlite.version = 1
        sqlite.close()

        val db = Room.databaseBuilder(context, CenixDatabase::class.java, name)
            .addMigrations(
                CenixDatabase.MIGRATION_1_2,
                CenixDatabase.MIGRATION_2_3,
                CenixDatabase.MIGRATION_3_4,
                CenixDatabase.MIGRATION_4_5,
                CenixDatabase.MIGRATION_5_6,
                CenixDatabase.MIGRATION_6_7,
                CenixDatabase.MIGRATION_7_8,
                CenixDatabase.MIGRATION_8_9,
            )
            .allowMainThreadQueries()
            .build()
        val metadata = db.dao().metadata()
        assertNotNull(metadata)
        assertEquals(2, metadata!!.startupFailures)
        assertTrue(metadata.emergency)
        assertTrue(db.dao().workspaceItems().isEmpty())
        assertEquals(listOf(1L), db.dao().workspacePages().map { it.pageId })
        db.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migratesV2ToV8PreservesPagesHotseatAndProfiles() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "cenix-v2-v3.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val sqlite = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        sqlite.execSQL("CREATE TABLE `launcher_metadata` (`singletonId` INTEGER NOT NULL, `startupInProgress` INTEGER NOT NULL, `startupFailures` INTEGER NOT NULL, `lastFailureAt` INTEGER NOT NULL, `emergency` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`singletonId`))")
        sqlite.execSQL("INSERT INTO launcher_metadata VALUES (1,0,0,0,0,0)")
        sqlite.execSQL("CREATE TABLE `workspace_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `screen` INTEGER NOT NULL, `cellX` INTEGER NOT NULL, `cellY` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `className` TEXT NOT NULL, `profileId` INTEGER NOT NULL)")
        sqlite.execSQL("CREATE UNIQUE INDEX `index_workspace_items_screen_cellX_cellY` ON `workspace_items` (`screen`,`cellX`,`cellY`)")
        sqlite.execSQL("CREATE UNIQUE INDEX `index_workspace_items_packageName_className_profileId` ON `workspace_items` (`packageName`,`className`,`profileId`)")
        sqlite.execSQL("INSERT INTO workspace_items VALUES (7,0,1,2,'page0','Main',10),(8,1,2,3,'page1','Main',11),(9,-1,3,0,'dock','Main',12)")
        sqlite.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        sqlite.execSQL("INSERT INTO room_master_table VALUES(42,'bfd9985aefa862a91336911fc458b1ce')")
        sqlite.version = 2
        sqlite.close()
        val db = Room.databaseBuilder(context, CenixDatabase::class.java, name)
            .addMigrations(
                CenixDatabase.MIGRATION_2_3,
                CenixDatabase.MIGRATION_3_4,
                CenixDatabase.MIGRATION_4_5,
                CenixDatabase.MIGRATION_5_6,
                CenixDatabase.MIGRATION_6_7,
                CenixDatabase.MIGRATION_7_8,
                CenixDatabase.MIGRATION_8_9,
            )
            .allowMainThreadQueries()
            .build()
        val items = db.dao().workspaceItems().associateBy { it.id }
        assertEquals(1L, items[7]!!.containerId)
        assertEquals(2L, items[8]!!.containerId)
        assertEquals("HOTSEAT", items[9]!!.containerKind)
        assertEquals(12L, db.dao().workspaceApplications().associateBy { it.itemId }[9]!!.profileId)
        assertEquals(10L, db.dao().workspaceMetadata()!!.nextItemId)
        assertEquals(3L, db.dao().workspaceMetadata()!!.nextPageId)
        assertTrue(db.dao().workspaceShortcuts().isEmpty())
        assertEquals("4_by_5", db.dao().launcherSettings()!!.gridName)
        assertTrue(db.dao().launcherSettings()!!.notificationDots)
        assertFalse(db.dao().launcherSettings()!!.themedIcons)
        assertTrue(db.dao().launcherSettings()!!.autoAddApps)
        db.close()
        context.deleteDatabase(name)
    }

    @Test
    fun migratesV8ToV9AddsRestoreJournalAndWidgetSpanDefaults() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "cenix-v8-v9.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val sqlite = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        sqlite.execSQL("CREATE TABLE `launcher_metadata` (`singletonId` INTEGER NOT NULL, `startupInProgress` INTEGER NOT NULL, `startupFailures` INTEGER NOT NULL, `lastFailureAt` INTEGER NOT NULL, `emergency` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`singletonId`))")
        sqlite.execSQL("INSERT INTO launcher_metadata VALUES (1,0,0,0,0,0)")
        sqlite.execSQL("CREATE TABLE `workspace_metadata` (`singletonId` INTEGER NOT NULL, `generation` INTEGER NOT NULL, `cols` INTEGER NOT NULL, `rows` INTEGER NOT NULL, `hotseatCols` INTEGER NOT NULL, `nextItemId` INTEGER NOT NULL, `nextPageId` INTEGER NOT NULL, PRIMARY KEY(`singletonId`))")
        sqlite.execSQL("INSERT INTO workspace_metadata VALUES (1,4,4,5,4,8,2)")
        sqlite.execSQL("CREATE TABLE `launcher_settings` (`singletonId` INTEGER NOT NULL, `gridName` TEXT NOT NULL, `notificationDots` INTEGER NOT NULL, `themedIcons` INTEGER NOT NULL, `autoAddApps` INTEGER NOT NULL, PRIMARY KEY(`singletonId`))")
        sqlite.execSQL("INSERT INTO launcher_settings VALUES (1,'4_by_5',1,0,1)")
        sqlite.execSQL("CREATE TABLE `workspace_pages` (`pageId` INTEGER NOT NULL, `rank` INTEGER NOT NULL, PRIMARY KEY(`pageId`))")
        sqlite.execSQL("CREATE UNIQUE INDEX `index_workspace_pages_rank` ON `workspace_pages` (`rank`)")
        sqlite.execSQL("INSERT INTO workspace_pages VALUES (1,0)")
        sqlite.execSQL("CREATE TABLE `workspace_items` (`id` INTEGER NOT NULL, `containerKind` TEXT NOT NULL, `containerId` INTEGER NOT NULL, `cellX` INTEGER NOT NULL, `cellY` INTEGER NOT NULL, `spanX` INTEGER NOT NULL, `spanY` INTEGER NOT NULL, `itemKind` TEXT NOT NULL, PRIMARY KEY(`id`))")
        sqlite.execSQL("CREATE UNIQUE INDEX `index_workspace_items_containerKind_containerId_cellX_cellY` ON `workspace_items` (`containerKind`,`containerId`,`cellX`,`cellY`)")
        sqlite.execSQL("INSERT INTO workspace_items VALUES (7,'WORKSPACE',1,0,0,2,3,'WIDGET')")
        sqlite.execSQL("CREATE TABLE `workspace_applications` (`itemId` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `className` TEXT NOT NULL, `profileId` INTEGER NOT NULL, PRIMARY KEY(`itemId`))")
        sqlite.execSQL("CREATE UNIQUE INDEX `index_workspace_applications_packageName_className_profileId` ON `workspace_applications` (`packageName`,`className`,`profileId`)")
        sqlite.execSQL("CREATE TABLE `workspace_shortcuts` (`itemId` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `shortcutId` TEXT NOT NULL, `profileId` INTEGER NOT NULL, PRIMARY KEY(`itemId`))")
        sqlite.execSQL("CREATE UNIQUE INDEX `index_workspace_shortcuts_packageName_shortcutId_profileId` ON `workspace_shortcuts` (`packageName`,`shortcutId`,`profileId`)")
        sqlite.execSQL("CREATE TABLE `workspace_widgets` (`itemId` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `className` TEXT NOT NULL, `profileId` INTEGER NOT NULL, `appWidgetId` INTEGER, PRIMARY KEY(`itemId`))")
        sqlite.execSQL("CREATE UNIQUE INDEX `index_workspace_widgets_appWidgetId` ON `workspace_widgets` (`appWidgetId`)")
        sqlite.execSQL("INSERT INTO workspace_widgets VALUES (7,'widgets','Clock',0,42)")
        sqlite.execSQL("CREATE TABLE `pending_widget_operations` (`itemId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `phase` TEXT NOT NULL, `appWidgetId` INTEGER, `replacementAppWidgetId` INTEGER, `packageName` TEXT NOT NULL, `className` TEXT NOT NULL, `profileId` INTEGER NOT NULL, `pageId` INTEGER NOT NULL, `cellX` INTEGER NOT NULL, `cellY` INTEGER NOT NULL, `spanX` INTEGER NOT NULL, `spanY` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`itemId`))")
        sqlite.execSQL("CREATE UNIQUE INDEX `index_pending_widget_operations_appWidgetId` ON `pending_widget_operations` (`appWidgetId`)")
        sqlite.execSQL("INSERT INTO pending_widget_operations VALUES (7,'RESTORE','RESTORE_PENDING',42,NULL,'widgets','Clock',0,1,0,0,2,3,10)")
        sqlite.execSQL("CREATE TABLE `workspace_folders` (`folderId` INTEGER NOT NULL, `title` TEXT NOT NULL, PRIMARY KEY(`folderId`))")
        sqlite.execSQL("CREATE TABLE `folder_members` (`itemId` INTEGER NOT NULL, `folderId` INTEGER NOT NULL, `rank` INTEGER NOT NULL, PRIMARY KEY(`itemId`))")
        sqlite.execSQL("CREATE UNIQUE INDEX `index_folder_members_folderId_rank` ON `folder_members` (`folderId`,`rank`)")
        sqlite.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        sqlite.execSQL("INSERT INTO room_master_table VALUES(42,'0ae4a07dfbbc290789824b5c6d54dcc2')")
        sqlite.version = 8
        sqlite.close()
        val db = Room.databaseBuilder(context, CenixDatabase::class.java, name)
            .addMigrations(CenixDatabase.MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()
        val widget = db.dao().workspaceWidgets().single()
        assertEquals(1, widget.minSpanX)
        assertEquals(1, widget.minSpanY)
        assertEquals(2, widget.resizeX)
        assertEquals(3, widget.resizeY)
        assertEquals(42, widget.appWidgetId)
        assertEquals(4L, db.dao().workspaceMetadata()!!.generation)
        assertNull(db.dao().pendingRestore())
        assertEquals(7L, db.dao().pendingWidgetOperations().single().itemId)
        db.close()
        context.deleteDatabase(name)
    }

    @Test
    fun restoreJournalStagesPhasesRetriesAndCleansStale() {
        val db = openDb()
        val first = PendingRestoreOperationEntity(
            source = RestoreSource.LOCAL,
            phase = RestorePhase.PARSED,
            payload = "{}",
            payloadSha256 = BackupRepository.sha256("{}"),
            createdAt = 10,
            attemptCount = 0,
            expectedGeneration = 0,
        )
        db.dao().stageRestore(first)
        db.dao().stageRestore(first.copy(expectedGeneration = 1))
        assertEquals("{}", db.dao().pendingRestore()!!.payload)
        assertEquals(1L, db.dao().pendingRestore()!!.expectedGeneration)
        assertEquals(RestorePhase.PARSED, db.dao().retryRestore()!!.phase)
        assertEquals(0, db.dao().retryRestore()!!.attemptCount)
        assertEquals(1, db.dao().confirmLocalRestore())
        assertEquals(RestorePhase.USER_CONFIRMED, db.dao().pendingRestore()!!.phase)
        repeat(PendingRestoreOperationEntity.MAX_ATTEMPTS) {
            db.dao().recordRestoreFailure(first.payloadSha256)
            db.dao().retryRestore()
            if (it + 1 < PendingRestoreOperationEntity.MAX_ATTEMPTS) db.dao().confirmLocalRestore()
        }
        assertEquals(RestorePhase.FAILED, db.dao().pendingRestore()!!.phase)
        assertEquals(PendingRestoreOperationEntity.MAX_ATTEMPTS, db.dao().pendingRestore()!!.attemptCount)
        db.dao().cleanupStaleRestores(11)
        assertNull(db.dao().pendingRestore())
        assertThrows(InvalidWorkspaceTransition::class.java) {
            db.dao().stageRestore(first.copy(payloadSha256 = "not-a-checksum"))
        }
        db.close()
    }

    @Test
    fun restoreReplaceIsAtomicIdempotentAndUnbindsWidgets() {
        val db = openDb()
        db.dao().upsertPendingWidgetOperation(
            PendingWidgetOperationEntity(
                itemId = 9,
                kind = WidgetOperationKind.PIN,
                phase = WidgetOperationPhase.BOUND,
                appWidgetId = 7,
                replacementAppWidgetId = null,
                packageName = "old",
                className = "Old",
                profileId = 0,
                pageId = 1,
                cellX = 0,
                cellY = 0,
                spanX = 1,
                spanY = 1,
                updatedAt = 1,
            ),
        )
        db.dao().stageRestore(
            PendingRestoreOperationEntity(
                source = RestoreSource.SYSTEM,
                phase = RestorePhase.PARSED,
                payload = "plan",
                payloadSha256 = BackupRepository.sha256("plan"),
                createdAt = 1,
                attemptCount = 0,
                expectedGeneration = 0,
            ),
        )
        assertEquals(1, db.dao().queueSystemRestore())
        val pages = listOf(WorkspacePageEntity(1, 0))
        val items = listOf(
            WorkspaceItemEntity(1, WorkspaceItemEntity.CONTAINER_WORKSPACE, 1, 0, 0, 2, 2, WorkspaceItemEntity.ITEM_WIDGET),
        )
        val widgets = listOf(WidgetItemEntity(1, "widgets", "Clock", 0, 99, 2, 2, 4, 3))
        val metadata = WorkspaceMetadataEntity(generation = 1, cols = 4, rows = 5, hotseatCols = 4, nextItemId = 2, nextPageId = 2)
        val settings = LauncherSettingsEntity(gridName = "4_by_5", notificationDots = false, themedIcons = true, autoAddApps = false)
        assertThrows(InvalidWorkspaceTransition::class.java) {
            db.dao().replaceWorkspaceFromRestore(0, "wrong", metadata, pages, items, widgets = widgets, settings = settings)
        }
        assertEquals(0L, db.dao().workspaceMetadata()!!.generation)
        val planSha256 = BackupRepository.sha256("plan")
        db.dao().replaceWorkspaceFromRestore(0, planSha256, metadata, pages, items, widgets = widgets, settings = settings)
        assertEquals(1L, db.dao().workspaceMetadata()!!.generation)
        assertNull(db.dao().workspaceWidgets().single().appWidgetId)
        assertEquals(2, db.dao().workspaceWidgets().single().minSpanX)
        assertEquals(4, db.dao().workspaceWidgets().single().resizeX)
        assertTrue(db.dao().pendingWidgetOperations().isEmpty())
        assertEquals(RestorePhase.PLATFORM_RECONCILE, db.dao().pendingRestore()!!.phase)
        assertEquals(1L, db.dao().pendingRestore()!!.committedGeneration)
        assertEquals("4_by_5", db.dao().launcherSettings()!!.gridName)
        assertFalse(db.dao().launcherSettings()!!.notificationDots)
        db.dao().replaceWorkspaceFromRestore(0, planSha256, metadata, pages, items, widgets = widgets, settings = settings)
        assertEquals(1L, db.dao().workspaceMetadata()!!.generation)
        assertThrows(InvalidWorkspaceTransition::class.java) {
            db.dao().stageRestore(
                PendingRestoreOperationEntity(
                    source = RestoreSource.LOCAL,
                    phase = RestorePhase.PARSED,
                    payload = "other",
                    payloadSha256 = BackupRepository.sha256("other"),
                    createdAt = 2,
                    attemptCount = 0,
                    expectedGeneration = 1,
                ),
            )
        }
        assertThrows(StaleWorkspaceGeneration::class.java) {
            db.dao().replaceWorkspaceFromRestore(
                9,
                planSha256,
                metadata.copy(generation = 10),
                pages,
                items,
                widgets = widgets,
                settings = settings,
            )
        }
        assertEquals(1L, db.dao().workspaceMetadata()!!.generation)
        assertTrue(db.dao().completeRestore(1))
        db.dao().stageRestore(
            PendingRestoreOperationEntity(
                source = RestoreSource.LOCAL,
                phase = RestorePhase.PARSED,
                payload = "other",
                payloadSha256 = BackupRepository.sha256("other"),
                createdAt = 2,
                attemptCount = 0,
                expectedGeneration = 1,
            ),
        )
        assertEquals(RestorePhase.PARSED, db.dao().pendingRestore()!!.phase)
        db.close()
    }

    @Test
    fun restoreReplaceRollsBackOnInvalidRows() {
        val db = openDb()
        db.dao().stageRestore(
            PendingRestoreOperationEntity(
                source = RestoreSource.LOCAL,
                phase = RestorePhase.PARSED,
                payload = "plan",
                payloadSha256 = BackupRepository.sha256("plan"),
                createdAt = 1,
                attemptCount = 0,
                expectedGeneration = 0,
            ),
        )
        db.dao().confirmLocalRestore()
        assertThrows(InvalidWorkspaceTransition::class.java) {
            db.dao().replaceWorkspaceFromRestore(
                0,
                BackupRepository.sha256("plan"),
                WorkspaceMetadataEntity(generation = 1, cols = 4, rows = 5, hotseatCols = 4, nextItemId = 2, nextPageId = 2),
                listOf(WorkspacePageEntity(1, 0)),
                listOf(WorkspaceItemEntity(1, WorkspaceItemEntity.CONTAINER_WORKSPACE, 1, 0, 0, 1, 1, WorkspaceItemEntity.ITEM_WIDGET)),
                settings = LauncherSettingsEntity(gridName = "4_by_5"),
            )
        }
        assertEquals(0L, db.dao().workspaceMetadata()!!.generation)
        assertEquals(listOf(1L), db.dao().workspacePages().map { it.pageId })
        assertEquals(RestorePhase.USER_CONFIRMED, db.dao().pendingRestore()!!.phase)
        db.close()
    }

    @Test
    fun backupRepositoryAppliesPlanAndPreservesWidgetMetadataOnCommit() {
        val db = openDb()
        val repo = BackupRepository(db)
        repo.stage(RestoreSource.LOCAL, "{\"ok\":true}", 0, 5)
        repo.confirmLocal()
        repo.applyLocalPlan(
            BackupImportPlan(
                workspace = WorkspaceSnapshot(
                    generation = 1UL,
                    grid = GridSpec(4, 5, 4),
                    pages = listOf(WorkspacePage(1UL, 0)),
                    items = listOf(
                        WorkspaceItem(
                            1UL,
                            ItemPayload.Widget(WidgetProviderId("widgets", "Clock", 0UL)),
                            ContainerRef.Workspace(1UL),
                            CellRect(0, 0, 2, 1),
                        ),
                    ),
                    folders = emptyList(),
                ),
                settings = BackupSettings("4_by_5", true, false, true),
                profiles = emptyList(),
                widgets = listOf(BackupWidgetMetadata(1UL, 2, 1, 4, 2)),
                nextItemId = 2UL,
                nextPageId = 2UL,
                unresolvedApplications = emptyList(),
                unresolvedShortcuts = emptyList(),
                unresolvedWidgets = emptyList(),
                warnings = emptyList(),
            ),
            "{\"ok\":true}",
        )
        val widget = db.dao().workspaceWidgets().single()
        assertNull(repo.pending())
        assertNull(widget.appWidgetId)
        assertEquals(2, widget.minSpanX)
        assertEquals(4, widget.resizeX)
        db.dao().commitWorkspace(
            1,
            db.dao().workspaceMetadata()!!,
            db.dao().workspacePages(),
            db.dao().workspaceItems(),
            widgets = db.dao().workspaceWidgets(),
        )
        assertEquals(2, db.dao().workspaceWidgets().single().minSpanX)
        assertEquals(4, db.dao().workspaceWidgets().single().resizeX)
        db.close()
    }

    private fun openDb(): CenixDatabase {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return Room.inMemoryDatabaseBuilder(context, CenixDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { it.ensureSeed() }
    }
}
