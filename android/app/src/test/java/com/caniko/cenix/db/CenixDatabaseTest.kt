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
    fun migratesV1ToV2KeepsMetadata() {
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
            .addMigrations(CenixDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        val metadata = db.dao().metadata()
        assertNotNull(metadata)
        assertEquals(2, metadata!!.startupFailures)
        assertTrue(metadata.emergency)
        assertTrue(db.dao().workspaceItems().isEmpty())
        db.close()
        context.deleteDatabase(name)
    }

    private fun openDb(): CenixDatabase {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return Room.inMemoryDatabaseBuilder(context, CenixDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { it.ensureSeed() }
    }
}
