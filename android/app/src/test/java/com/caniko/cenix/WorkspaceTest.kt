package com.caniko.cenix

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.caniko.cenix.db.CenixDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkspaceTest {
    @Test
    fun pinFillsRowMajorAndSurvivesReopen() {
        val db = openDb()
        val grid = PhoneGrid("2_by_2", 2, 2, 200f, 200f)
        val workspace = Workspace(db)
        assertTrue(workspace.pin(app("a"), grid))
        assertTrue(workspace.pin(app("b"), grid))
        val first = workspace.items()
        assertEquals(0, first[0].cellX)
        assertEquals(0, first[0].cellY)
        assertEquals(1, first[1].cellX)
        assertEquals(0, first[1].cellY)
        db.close()
    }

    @Test
    fun overflowGoesToSecondScreen() {
        val db = openDb()
        val grid = PhoneGrid("2_by_2", 2, 2, 200f, 200f)
        val workspace = Workspace(db)
        repeat(4) { i -> assertTrue(workspace.pin(app("p$i"), grid)) }
        assertNull(workspace.firstEmpty(grid, 0))
        assertTrue(workspace.pin(app("overflow"), grid))
        assertEquals(1, workspace.items().last().screen)
        db.close()
    }

    @Test
    fun preferredScreenUsedWhenEmpty() {
        val db = openDb()
        val grid = PhoneGrid("2_by_2", 2, 2, 200f, 200f)
        val workspace = Workspace(db)
        assertTrue(workspace.pin(app("second"), grid, preferred = 1))
        assertEquals(1, workspace.items().single().screen)
        db.close()
    }

    @Test
    fun fullGridRejectsPin() {
        val db = openDb()
        val grid = PhoneGrid("2_by_2", 2, 2, 200f, 200f)
        val workspace = Workspace(db)
        repeat(Workspace.SCREENS * 4) { i -> assertTrue(workspace.pin(app("p$i"), grid)) }
        assertEquals(false, workspace.pin(app("overflow"), grid))
        assertNull(workspace.firstEmpty(grid, 0))
        assertNull(workspace.firstEmpty(grid, 1))
        db.close()
    }

    @Test
    fun unpinAndDropMissing() {
        val db = openDb()
        val grid = PhoneGrid("3_by_3", 3, 3, 255f, 300f)
        val workspace = Workspace(db)
        val keep = app("keep")
        val gone = app("gone")
        workspace.pin(keep, grid)
        workspace.pin(gone, grid)
        workspace.unpin(gone)
        assertEquals(1, workspace.items().size)
        workspace.pin(gone, grid)
        workspace.dropMissing(setOf(Triple(keep.packageName, keep.className, keep.profileId)))
        assertEquals(listOf("keep"), workspace.items().map { it.packageName })
        db.close()
    }

    @Test
    fun outOfGridCellsStayStored() {
        val db = openDb()
        db.dao().insertWorkspace(
            com.caniko.cenix.db.WorkspaceItemEntity(
                screen = 0,
                cellX = 4,
                cellY = 4,
                packageName = "x",
                className = "Y",
                profileId = 0,
            ),
        )
        val grid = PhoneGrid("2_by_2", 2, 2, 200f, 200f)
        assertEquals(true, PhoneGrid.PHONE.first().inBounds(0, 0))
        assertEquals(false, grid.inBounds(4, 4))
        assertEquals(1, Workspace(db).items().size)
        db.close()
    }

    private fun app(pkg: String) =
        LaunchableApp(pkg, "Main", 0, pkg, pkg, null, null)

    private fun openDb(): CenixDatabase {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return Room.inMemoryDatabaseBuilder(context, CenixDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { it.ensureSeed() }
    }
}
