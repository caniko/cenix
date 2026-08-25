package com.caniko.cenix

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.caniko.cenix.db.CenixDatabase
import org.junit.Assert.assertEquals
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
    fun migration2To5PreservesWorkspaceAndHotseat() {
        helper.createDatabase(NAME, 2).apply {
            execSQL("INSERT INTO launcher_metadata VALUES (1,0,0,0,0,0)")
            execSQL("INSERT INTO workspace_items VALUES (7,0,1,2,'page0','Main',10),(8,1,2,3,'page1','Main',11),(9,-1,3,0,'dock','Main',12)")
            close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, CenixDatabase::class.java, NAME)
            .addMigrations(CenixDatabase.MIGRATION_2_3, CenixDatabase.MIGRATION_3_4, CenixDatabase.MIGRATION_4_5)
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
    fun migration3To5NormalizesApplicationIdentity() {
        helper.createDatabase(NAME_V3, 3).apply {
            execSQL("INSERT INTO launcher_metadata VALUES (1,0,0,0,0,0)")
            execSQL("INSERT INTO workspace_metadata VALUES (1,4,4,5,4,8,2)")
            execSQL("INSERT INTO workspace_pages VALUES (1,0)")
            execSQL("INSERT INTO workspace_items VALUES (7,'WORKSPACE',1,1,2,1,1,'APPLICATION','pkg','Main',10)")
            close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, CenixDatabase::class.java, NAME_V3)
            .addMigrations(CenixDatabase.MIGRATION_3_4, CenixDatabase.MIGRATION_4_5)
            .build()
        assertEquals(7L, database.dao().workspaceItems().single().id)
        assertEquals("pkg", database.dao().workspaceApplications().single().packageName)
        assertEquals(10L, database.dao().workspaceApplications().single().profileId)
        database.close()
        context.deleteDatabase(NAME_V3)
    }

    @Test
    fun migration4To5PreservesFoldersAndAllocators() {
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
            .addMigrations(CenixDatabase.MIGRATION_4_5)
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

    companion object {
        private const val NAME = "workspace-migration-test"
        private const val NAME_V3 = "workspace-migration-v3-test"
        private const val NAME_V4 = "workspace-migration-v4-test"
    }
}
