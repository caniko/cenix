package com.caniko.cenix

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.caniko.cenix.db.CenixDatabase
import com.caniko.cenix.db.WidgetItemEntity
import com.caniko.cenix.db.WorkspaceItemEntity
import com.caniko.cenix.db.WorkspaceMetadataEntity
import com.caniko.cenix.db.WorkspacePageEntity
import com.caniko.cenix.uniffi.WidgetMinimumSpan
import com.caniko.cenix.uniffi.WorkspaceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GridTransactionTest {
    @Test
    fun gridAndSettingCommitTogetherAndImpossibleWidgetChangesNeither() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, CenixDatabase::class.java).build()
        val two = checkNotNull(PhoneGrid.named("2_by_2"))
        val three = checkNotNull(PhoneGrid.named("3_by_3"))
        database.ensureSeed(two)
        database.dao().commitWorkspace(
            0,
            WorkspaceMetadataEntity(generation = 1, cols = 2, rows = 2, hotseatCols = 2, nextItemId = 2),
            listOf(WorkspacePageEntity(1, 0)),
            listOf(
                WorkspaceItemEntity(
                    1,
                    WorkspaceItemEntity.CONTAINER_WORKSPACE,
                    1,
                    0,
                    0,
                    2,
                    2,
                    WorkspaceItemEntity.ITEM_WIDGET,
                ),
            ),
            widgets = listOf(WidgetItemEntity(1, "widgets", "Clock", 0, null)),
        )
        val controller = WorkspaceController(LauncherRepository(database)) { true }

        assertThrows(WorkspaceException.WidgetTooLarge::class.java) {
            controller.setGrid(three, listOf(WidgetMinimumSpan(1UL, 4, 1)))
        }
        assertEquals("2_by_2", database.dao().launcherSettings()!!.gridName)
        assertEquals(1L, database.dao().workspaceMetadata()!!.generation)
        assertEquals(2, database.dao().workspaceItems().single().spanX)

        controller.setGrid(three, listOf(WidgetMinimumSpan(1UL, 1, 1)))
        assertEquals("3_by_3", database.dao().launcherSettings()!!.gridName)
        assertEquals(2L, database.dao().workspaceMetadata()!!.generation)
        assertEquals(1L, database.dao().workspaceItems().single().id)
        assertEquals("Clock", database.dao().workspaceWidgets().single().className)
        database.close()
    }
}
