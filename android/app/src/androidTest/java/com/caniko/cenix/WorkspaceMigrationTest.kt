package com.caniko.cenix

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.caniko.cenix.db.CenixDatabase
import org.junit.Assert.assertEquals
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
    fun migration2To3PreservesWorkspaceAndHotseat() {
        helper.createDatabase(NAME, 2).apply {
            execSQL("INSERT INTO launcher_metadata VALUES (1,0,0,0,0,0)")
            execSQL("INSERT INTO workspace_items VALUES (7,0,1,2,'page0','Main',10),(8,1,2,3,'page1','Main',11),(9,-1,3,0,'dock','Main',12)")
            close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, CenixDatabase::class.java, NAME)
            .addMigrations(CenixDatabase.MIGRATION_2_3)
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

    companion object {
        private const val NAME = "workspace-migration-test"
    }
}
