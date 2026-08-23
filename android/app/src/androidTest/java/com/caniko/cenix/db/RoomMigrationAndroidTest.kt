package com.caniko.cenix.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMigrationAndroidTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CenixDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migratesV1ToV2OnDevice() {
        helper.createDatabase("device-mig.db", 1).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS launcher_metadata (
                  singletonId INTEGER NOT NULL PRIMARY KEY,
                  schemaVersion INTEGER NOT NULL,
                  generation INTEGER NOT NULL,
                  createdAt INTEGER NOT NULL,
                  updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            execSQL("INSERT INTO launcher_metadata VALUES (1, 1, 2, 3, 4)")
            close()
        }
        val db = helper.runMigrationsAndValidate("device-mig.db", 2, true, CenixDatabase.MIGRATION_1_2)
        val cursor = db.query("SELECT lastHealthyGeneration FROM launcher_metadata")
        cursor.moveToFirst()
        assertEquals(0L, cursor.getLong(0))
        cursor.close()
        db.close()
    }
}
