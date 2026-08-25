package com.caniko.cenix

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.caniko.cenix.db.CenixDatabase
import com.caniko.cenix.db.ApplicationItemEntity
import com.caniko.cenix.db.FolderEntity
import com.caniko.cenix.db.FolderMemberEntity
import com.caniko.cenix.db.StaleWorkspaceGeneration
import com.caniko.cenix.db.InvalidWorkspaceTransition
import com.caniko.cenix.db.WorkspaceMetadataEntity
import com.caniko.cenix.db.WorkspacePageEntity
import com.caniko.cenix.db.WorkspaceItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkspaceTest {
    @Test
    fun committedTransitionAdvancesOneGeneration() {
        val db = openDb()
        db.dao().commitWorkspace(
            expectedGeneration = 0,
            metadata = WorkspaceMetadataEntity(generation = 1, cols = 4, rows = 4, hotseatCols = 4),
            pages = listOf(WorkspacePageEntity(1, 0)),
            items = listOf(item(1, "WORKSPACE", 1)),
            applications = listOf(app(1, "a")),
        )
        assertEquals(1, db.dao().workspaceMetadata()!!.generation)
        assertEquals("a", db.dao().workspaceApplications().single().packageName)
        db.close()
    }

    @Test
    fun staleGenerationRejectsBeforeWrites() {
        val db = openDb()
        assertThrows(StaleWorkspaceGeneration::class.java) {
            db.dao().commitWorkspace(
                expectedGeneration = 9,
                metadata = WorkspaceMetadataEntity(generation = 10, cols = 4, rows = 4, hotseatCols = 4),
                pages = listOf(WorkspacePageEntity(1, 0)),
                items = emptyList(),
            )
        }
        assertEquals(0, db.dao().workspaceMetadata()!!.generation)
        db.close()
    }

    @Test
    fun failedRoomWriteRollsBackGeneration() {
        val db = openDb()
        assertThrows(Exception::class.java) {
            db.dao().commitWorkspace(
                expectedGeneration = 0,
                metadata = WorkspaceMetadataEntity(generation = 1, cols = 4, rows = 4, hotseatCols = 4),
                pages = listOf(WorkspacePageEntity(1, 0), WorkspacePageEntity(2, 0)),
                items = emptyList(),
            )
        }
        assertEquals(0, db.dao().workspaceMetadata()!!.generation)
        assertEquals(listOf(1L), db.dao().workspacePages().map { it.pageId })
        db.close()
    }

    @Test
    fun hotseatAndPageContainersRoundTrip() {
        val db = openDb()
        db.dao().commitWorkspace(
            expectedGeneration = 0,
            metadata = WorkspaceMetadataEntity(generation = 1, cols = 4, rows = 4, hotseatCols = 4),
            pages = listOf(WorkspacePageEntity(1, 0)),
            items = listOf(item(1, "HOTSEAT", 0)),
            applications = listOf(app(1, "dock")),
        )
        assertEquals("HOTSEAT", db.dao().workspaceItems().single().containerKind)
        db.close()
    }

    @Test
    fun noOpMustMatchEveryPersistedRow() {
        val db = openDb()
        val metadata = db.dao().workspaceMetadata()!!
        val pages = db.dao().workspacePages()
        db.dao().commitWorkspace(0, metadata, pages, emptyList())
        assertThrows(InvalidWorkspaceTransition::class.java) {
            db.dao().commitWorkspace(0, metadata, pages, listOf(item(1, "WORKSPACE", 1)))
        }
        assertTrue(db.dao().workspaceItems().isEmpty())
        db.close()
    }

    @Test
    fun allocatedIdsRemainMonotonicAfterRemoval() {
        val db = openDb()
        val pages = listOf(WorkspacePageEntity(1, 0))
        db.dao().commitWorkspace(
            0,
            WorkspaceMetadataEntity(generation = 1, cols = 4, rows = 4, hotseatCols = 4, nextItemId = 2, nextPageId = 2),
            pages,
            listOf(item(1, "WORKSPACE", 1)),
            listOf(app(1, "a")),
        )
        db.dao().commitWorkspace(
            1,
            WorkspaceMetadataEntity(generation = 2, cols = 4, rows = 4, hotseatCols = 4, nextItemId = 2, nextPageId = 2),
            pages,
            emptyList(),
        )
        assertEquals(2L, db.dao().workspaceMetadata()!!.nextItemId)
        db.close()
    }

    @Test
    fun normalizedFolderRowsCommitAndNoOpAtomically() {
        val db = openDb()
        val metadata = WorkspaceMetadataEntity(generation = 1, cols = 4, rows = 4, hotseatCols = 4, nextItemId = 11)
        val pages = listOf(WorkspacePageEntity(1, 0))
        val items = listOf(item(10, "WORKSPACE", 1).copy(itemKind = WorkspaceItemEntity.ITEM_FOLDER))
        val applications = listOf(app(1, "a"), app(2, "b"))
        val folders = listOf(FolderEntity(10, "Tools"))
        val members = listOf(FolderMemberEntity(1, 10, 0), FolderMemberEntity(2, 10, 1))
        db.dao().commitWorkspace(0, metadata, pages, items, applications, folders, members)

        val rows = db.dao().workspaceState()
        assertEquals(listOf(1L, 2L), rows.folderMembers.map { it.itemId })
        db.dao().commitWorkspace(1, metadata, pages, items, applications, folders, members)
        assertEquals(1L, db.dao().workspaceMetadata()!!.generation)
        db.close()
    }

    private fun item(id: Long, container: String, containerId: Long) = WorkspaceItemEntity(
        id = id,
        containerKind = container,
        containerId = containerId,
        cellX = 0,
        cellY = 0,
    )

    private fun app(id: Long, pkg: String) = ApplicationItemEntity(id, pkg, "Main", 0)

    private fun openDb(): CenixDatabase {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return Room.inMemoryDatabaseBuilder(context, CenixDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { it.ensureSeed() }
    }
}
