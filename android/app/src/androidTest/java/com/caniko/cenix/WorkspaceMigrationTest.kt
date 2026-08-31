package com.caniko.cenix

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.caniko.cenix.db.CenixDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CenixDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration2To8PreservesWorkspaceAndHotseat() {
        helper.createDatabase(NAME, 2).apply {
            execSQL("INSERT INTO launcher_metadata VALUES (1,0,0,0,0,0)")
            execSQL("INSERT INTO workspace_items VALUES (7,0,1,2,'page0','Main',10),(8,1,2,3,'page1','Main',11),(9,-1,3,0,'dock','Main',12)")
            close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, CenixDatabase::class.java, NAME)
            .addMigrations(CenixDatabase.MIGRATION_2_3, CenixDatabase.MIGRATION_3_4, CenixDatabase.MIGRATION_4_5, CenixDatabase.MIGRATION_5_6, CenixDatabase.MIGRATION_6_7, CenixDatabase.MIGRATION_7_8, CenixDatabase.MIGRATION_8_9)
            .build()
        val items = database.dao().workspaceItems().associateBy { it.id }
        assertEquals(1L, items[7]!!.containerId)
        assertEquals(2L, items[8]!!.containerId)
        assertEquals("HOTSEAT", items[9]!!.containerKind)
        assertEquals(10L, database.dao().workspaceMetadata()!!.nextItemId)
        assertEquals(3L, database.dao().workspaceMetadata()!!.nextPageId)
        database.close()
        context.deleteDatabase(NAME)
    }

    @Test
    fun migration3To8NormalizesApplicationIdentity() {
        helper.createDatabase(NAME_V3, 3).apply {
            execSQL("INSERT INTO launcher_metadata VALUES (1,0,0,0,0,0)")
            execSQL("INSERT INTO workspace_metadata VALUES (1,4,4,5,4,8,2)")
            execSQL("INSERT INTO workspace_pages VALUES (1,0)")
            execSQL("INSERT INTO workspace_items VALUES (7,'WORKSPACE',1,1,2,1,1,'APPLICATION','pkg','Main',10)")
            close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, CenixDatabase::class.java, NAME_V3)
            .addMigrations(CenixDatabase.MIGRATION_3_4, CenixDatabase.MIGRATION_4_5, CenixDatabase.MIGRATION_5_6, CenixDatabase.MIGRATION_6_7, CenixDatabase.MIGRATION_7_8, CenixDatabase.MIGRATION_8_9)
            .build()
        assertEquals(7L, database.dao().workspaceItems().single().id)
        assertEquals("pkg", database.dao().workspaceApplications().single().packageName)
        assertEquals(10L, database.dao().workspaceApplications().single().profileId)
        database.close()
        context.deleteDatabase(NAME_V3)
    }

    @Test
    fun migration4To8PreservesFoldersAndAllocators() {
        helper.createDatabase(NAME_V4, 4).apply {
            execSQL("INSERT INTO launcher_metadata VALUES (1,0,1,9,0,10)")
            execSQL("INSERT INTO workspace_metadata VALUES (1,7,4,5,4,20,3)")
            execSQL("INSERT INTO workspace_pages VALUES (1,0),(2,1)")
            execSQL("INSERT INTO workspace_items VALUES (10,'WORKSPACE',2,1,2,1,1,'FOLDER')")
            execSQL("INSERT INTO workspace_applications VALUES (11,'a','Main',0),(12,'b','Main',0)")
            execSQL("INSERT INTO workspace_folders VALUES (10,'Tools')")
            execSQL("INSERT INTO folder_members VALUES (11,10,0),(12,10,1)")
            close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, CenixDatabase::class.java, NAME_V4)
            .addMigrations(CenixDatabase.MIGRATION_4_5, CenixDatabase.MIGRATION_5_6, CenixDatabase.MIGRATION_6_7, CenixDatabase.MIGRATION_7_8, CenixDatabase.MIGRATION_8_9)
            .build()
        assertEquals(7L, database.dao().workspaceMetadata()!!.generation)
        assertEquals(20L, database.dao().workspaceMetadata()!!.nextItemId)
        assertEquals(3L, database.dao().workspaceMetadata()!!.nextPageId)
        assertEquals("Tools", database.dao().workspaceFolders().single().title)
        assertEquals(listOf(11L, 12L), database.dao().folderMembers().map { it.itemId })
        assertTrue(database.dao().workspaceShortcuts().isEmpty())
        database.close()
        context.deleteDatabase(NAME_V4)
    }

    @Test
    fun migration5To8PreservesP2bStateAndCreatesEmptyWidgetTables() {
        helper.createDatabase(NAME_V5, 5).apply {
            execSQL("INSERT INTO launcher_metadata VALUES (1,0,1,9,0,10)")
            execSQL("INSERT INTO workspace_metadata VALUES (1,7,4,5,4,20,3)")
            execSQL("INSERT INTO workspace_pages VALUES (1,0),(2,1)")
            execSQL("INSERT INTO workspace_items VALUES (10,'WORKSPACE',2,1,2,1,1,'FOLDER'),(13,'HOTSEAT',0,0,0,1,1,'SHORTCUT')")
            execSQL("INSERT INTO workspace_applications VALUES (11,'a','Main',0),(12,'b','Main',0)")
            execSQL("INSERT INTO workspace_shortcuts VALUES (13,'a','manifest',0)")
            execSQL("INSERT INTO workspace_folders VALUES (10,'Tools')")
            execSQL("INSERT INTO folder_members VALUES (11,10,0),(12,10,1)")
            close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, CenixDatabase::class.java, NAME_V5)
            .addMigrations(CenixDatabase.MIGRATION_5_6, CenixDatabase.MIGRATION_6_7, CenixDatabase.MIGRATION_7_8, CenixDatabase.MIGRATION_8_9)
            .build()
        assertEquals(7L, database.dao().workspaceMetadata()!!.generation)
        assertEquals(listOf(10L, 13L), database.dao().workspaceItems().map { it.id })
        assertEquals(listOf(11L, 12L), database.dao().workspaceApplications().map { it.itemId })
        assertEquals("manifest", database.dao().workspaceShortcuts().single().shortcutId)
        assertEquals("Tools", database.dao().workspaceFolders().single().title)
        assertTrue(database.dao().workspaceWidgets().isEmpty())
        assertTrue(database.dao().pendingWidgetOperations().isEmpty())
        assertEquals("4_by_5", database.dao().launcherSettings()!!.gridName)
        database.close()
        context.deleteDatabase(NAME_V5)
    }

    @Test
    fun migration6To8DerivesNormalizedGridSettingWithoutChangingWorkspace() {
        helper.createDatabase(NAME_V6, 6).apply {
            execSQL("INSERT INTO launcher_metadata VALUES (1,0,0,0,0,0)")
            execSQL("INSERT INTO workspace_metadata VALUES (1,9,5,5,5,20,3)")
            execSQL("INSERT INTO workspace_pages VALUES (1,0)")
            execSQL("INSERT INTO workspace_items VALUES (7,'WORKSPACE',1,4,4,1,1,'APPLICATION')")
            execSQL("INSERT INTO workspace_applications VALUES (7,'pkg','Main',10)")
            close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, CenixDatabase::class.java, NAME_V6)
            .addMigrations(CenixDatabase.MIGRATION_6_7, CenixDatabase.MIGRATION_7_8, CenixDatabase.MIGRATION_8_9)
            .build()
        assertEquals("5_by_5", database.dao().launcherSettings()!!.gridName)
        assertTrue(database.dao().launcherSettings()!!.notificationDots)
        assertEquals(false, database.dao().launcherSettings()!!.themedIcons)
        assertTrue(database.dao().launcherSettings()!!.autoAddApps)
        assertEquals(9L, database.dao().workspaceMetadata()!!.generation)
        assertEquals(7L, database.dao().workspaceItems().single().id)
        database.close()
        context.deleteDatabase(NAME_V6)
    }

    @Test
    fun migration7To8PreservesWorkspaceAndAddsReferenceDefaults() {
        helper.createDatabase(NAME_V7, 7).apply {
            execSQL("INSERT INTO launcher_metadata VALUES (1,0,0,0,0,0)")
            execSQL("INSERT INTO workspace_metadata VALUES (1,9,4,5,4,20,3)")
            execSQL("INSERT INTO workspace_pages VALUES (1,0)")
            execSQL("INSERT INTO workspace_items VALUES (7,'WORKSPACE',1,1,2,1,1,'APPLICATION')")
            execSQL("INSERT INTO workspace_applications VALUES (7,'pkg','Main',10)")
            execSQL("INSERT INTO launcher_settings VALUES (1,'4_by_5')")
            close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, CenixDatabase::class.java, NAME_V7)
            .addMigrations(CenixDatabase.MIGRATION_7_8, CenixDatabase.MIGRATION_8_9)
            .build()
        val settings = database.dao().launcherSettings()!!
        assertEquals("4_by_5", settings.gridName)
        assertTrue(settings.notificationDots)
        assertEquals(false, settings.themedIcons)
        assertTrue(settings.autoAddApps)
        assertEquals(9L, database.dao().workspaceMetadata()!!.generation)
        assertEquals(7L, database.dao().workspaceItems().single().id)
        database.close()
        context.deleteDatabase(NAME_V7)
    }

    @Test
    fun migration8To9AddsRestoreJournalAndWidgetSpanDefaults() {
        helper.createDatabase(NAME_V8, 8).apply {
            execSQL("INSERT INTO launcher_metadata VALUES (1,0,0,0,0,0)")
            execSQL("INSERT INTO workspace_metadata VALUES (1,9,4,5,4,20,3)")
            execSQL("INSERT INTO workspace_pages VALUES (1,0)")
            execSQL("INSERT INTO workspace_items VALUES (7,'WORKSPACE',1,0,0,2,3,'WIDGET')")
            execSQL("INSERT INTO workspace_widgets VALUES (7,'widgets','Clock',0,42)")
            execSQL("INSERT INTO launcher_settings VALUES (1,'4_by_5',1,0,1)")
            close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, CenixDatabase::class.java, NAME_V8)
            .addMigrations(CenixDatabase.MIGRATION_8_9)
            .build()
        val widget = database.dao().workspaceWidgets().single()
        assertEquals(1, widget.minSpanX)
        assertEquals(1, widget.minSpanY)
        assertEquals(2, widget.resizeX)
        assertEquals(3, widget.resizeY)
        assertEquals(42, widget.appWidgetId)
        assertEquals(9L, database.dao().workspaceMetadata()!!.generation)
        assertNull(database.dao().pendingRestore())
        database.close()
        context.deleteDatabase(NAME_V8)
    }

    companion object {
        private const val NAME = "workspace-migration-test"
        private const val NAME_V3 = "workspace-migration-v3-test"
        private const val NAME_V4 = "workspace-migration-v4-test"
        private const val NAME_V5 = "workspace-migration-v5-test"
        private const val NAME_V6 = "workspace-migration-v6-test"
        private const val NAME_V7 = "workspace-migration-v7-test"
        private const val NAME_V8 = "workspace-migration-v8-test"
    }
}
