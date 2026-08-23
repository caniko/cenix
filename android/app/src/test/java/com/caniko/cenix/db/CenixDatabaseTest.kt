package com.caniko.cenix.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.caniko.cenix.StartupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CenixDatabaseTest {
    @Test
    fun snapshotReplaceBumpsGeneration() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, CenixDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.ensureSeed()
        val first = db.dao().replaceSnapshot(
            listOf(
                AppSnapshotEntity("a/A/0", "a", "A", 0, "Alpha", 0),
            ),
            1,
        )
        val second = db.dao().replaceSnapshot(
            listOf(
                AppSnapshotEntity("b/B/0", "b", "B", 0, "Bravo", 0),
            ),
            2,
        )
        assertEquals(first + 1, second)
        assertEquals(listOf("b"), db.dao().snapshot().map { it.packageName })
        db.close()
    }

    @Test
    fun startupStateRoundTrip() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, CenixDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.ensureSeed()
        val store = RoomStartupStore(db)
        store.save(StartupState(inProgress = true, failures = 2, lastFailureAt = 9, emergency = false))
        val loaded = store.load()
        assertEquals(2, loaded.failures)
        assertFalse(loaded.emergency)
        assertNotNull(db.dao().metadata())
        db.close()
    }

    @Test
    fun seedCreatesMetadata() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, CenixDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.ensureSeed()
        assertNotNull(db.dao().metadata())
        db.close()
    }
}
