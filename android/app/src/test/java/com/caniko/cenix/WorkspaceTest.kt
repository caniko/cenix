package com.caniko.cenix

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.caniko.cenix.db.CenixDatabase
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
            items = listOf(item(1, "WORKSPACE", 1, "a")),
        )
        assertEquals(1, db.dao().workspaceMetadata()!!.generation)
        assertEquals("a", db.dao().workspaceItems().single().packageName)
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
            items = listOf(item(1, "HOTSEAT", 0, "dock")),
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
            db.dao().commitWorkspace(0, metadata, pages, listOf(item(1, "WORKSPACE", 1, "unexpected")))
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
            listOf(item(1, "WORKSPACE", 1, "a")),
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

    private fun item(id: Long, container: String, containerId: Long, pkg: String) = WorkspaceItemEntity(
        id = id,
        containerKind = container,
        containerId = containerId,
        cellX = 0,
        cellY = 0,
        packageName = pkg,
        className = "Main",
        profileId = 0,
    )

    private fun openDb(): CenixDatabase {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return Room.inMemoryDatabaseBuilder(context, CenixDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { it.ensureSeed() }
    }
}
