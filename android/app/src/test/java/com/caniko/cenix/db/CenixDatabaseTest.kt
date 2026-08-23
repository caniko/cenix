package com.caniko.cenix.db

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CenixDatabaseTest {
    @Test
    fun generationIncrementsOnReload() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, CenixDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.ensureSeed()
        val first = db.dao().bumpGeneration(1)
        val second = db.dao().bumpGeneration(2)
        assertEquals(first + 1, second)
        db.dao().markHealthy(second, 3)
        assertEquals(second, db.dao().metadata()?.lastHealthyGeneration)
        db.close()
    }

    @Test
    fun migratesV1ToV2() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE launcher_metadata (
                                  singletonId INTEGER NOT NULL PRIMARY KEY,
                                  schemaVersion INTEGER NOT NULL,
                                  generation INTEGER NOT NULL,
                                  createdAt INTEGER NOT NULL,
                                  updatedAt INTEGER NOT NULL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL("INSERT INTO launcher_metadata VALUES (1, 1, 4, 10, 20)")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                    },
                )
                .build(),
        )
        val db = helper.writableDatabase
        CenixDatabase.MIGRATION_1_2.migrate(db)
        val cursor = db.query("SELECT lastHealthyGeneration FROM launcher_metadata WHERE singletonId = 1")
        cursor.moveToFirst()
        assertEquals(0, cursor.getLong(0))
        cursor.close()
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
