package com.caniko.cenix.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.caniko.cenix.CrashLoopGuard
import com.caniko.cenix.StartupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertTrue(second.isEmergency())
        assertFalse(second.beginStartup())
        db.close()
    }

    @Test
    fun seedCreatesMetadata() {
        val db = openDb()
        assertNotNull(db.dao().metadata())
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
