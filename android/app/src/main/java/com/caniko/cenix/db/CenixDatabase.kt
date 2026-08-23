package com.caniko.cenix.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "launcher_metadata")
data class MetadataEntity(
    @PrimaryKey val singletonId: Int = 1,
    val schemaVersion: Int,
    val generation: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val lastHealthyGeneration: Long,
)

@Entity(tableName = "launcher_setting")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedGeneration: Long,
)

@Dao
interface CenixDao {
    @Query("SELECT * FROM launcher_metadata WHERE singletonId = 1")
    fun metadata(): MetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertMetadata(entity: MetadataEntity)

    @Query("SELECT * FROM launcher_setting WHERE `key` = :key")
    fun setting(key: String): SettingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSetting(entity: SettingEntity)

    @Query("DELETE FROM launcher_setting")
    fun clearSettings()

    @Transaction
    fun bumpGeneration(now: Long): Long {
        val current = metadata() ?: MetadataEntity(
            schemaVersion = CenixDatabase.VERSION,
            generation = 0,
            createdAt = now,
            updatedAt = now,
            lastHealthyGeneration = 0,
        )
        val next = current.copy(generation = current.generation + 1, updatedAt = now)
        upsertMetadata(next)
        return next.generation
    }

    @Transaction
    fun markHealthy(generation: Long, now: Long) {
        val current = metadata() ?: return
        upsertMetadata(current.copy(lastHealthyGeneration = generation, updatedAt = now))
    }
}

@Database(
    entities = [MetadataEntity::class, SettingEntity::class],
    version = CenixDatabase.VERSION,
    exportSchema = true,
)
abstract class CenixDatabase : RoomDatabase() {
    abstract fun dao(): CenixDao

    companion object {
        const val NAME = "cenix.db"
        const val VERSION = 2
        const val EMERGENCY_KEY = "emergency"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE launcher_metadata ADD COLUMN lastHealthyGeneration INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        fun open(context: Context): CenixDatabase =
            Room.databaseBuilder(context.applicationContext, CenixDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .allowMainThreadQueries()
                .build()
                .also { it.ensureSeed() }
    }

    fun ensureSeed() {
        if (dao().metadata() == null) {
            val now = System.currentTimeMillis()
            dao().upsertMetadata(
                MetadataEntity(
                    schemaVersion = VERSION,
                    generation = 1,
                    createdAt = now,
                    updatedAt = now,
                    lastHealthyGeneration = 0,
                ),
            )
        }
    }

    fun isEmergency(): Boolean = dao().setting(EMERGENCY_KEY)?.value == "1"

    fun setEmergency(value: Boolean) {
        dao().upsertSetting(
            SettingEntity(
                key = EMERGENCY_KEY,
                value = if (value) "1" else "0",
                updatedGeneration = dao().metadata()?.generation ?: 0,
            ),
        )
    }
}
