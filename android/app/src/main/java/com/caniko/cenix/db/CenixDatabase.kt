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
import com.caniko.cenix.StartupState
import com.caniko.cenix.StartupStore

@Entity(tableName = "launcher_metadata")
data class MetadataEntity(
    @PrimaryKey val singletonId: Int = 1,
    val generation: Long,
    val startupInProgress: Boolean,
    val startupFailures: Int,
    val lastFailureAt: Long,
    val emergency: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "app_snapshot")
data class AppSnapshotEntity(
    @PrimaryKey val id: String,
    val packageName: String,
    val className: String,
    val profileSerial: Long,
    val label: String,
    val generation: Long,
)

@Dao
interface CenixDao {
    @Query("SELECT * FROM launcher_metadata WHERE singletonId = 1")
    fun metadata(): MetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertMetadata(entity: MetadataEntity)

    @Query("SELECT * FROM app_snapshot ORDER BY label, packageName, className, profileSerial")
    fun snapshot(): List<AppSnapshotEntity>

    @Query("DELETE FROM app_snapshot")
    fun clearSnapshot()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSnapshot(apps: List<AppSnapshotEntity>)

    @Transaction
    fun saveStartup(state: StartupState, now: Long) {
        val current = metadata() ?: MetadataEntity(
            generation = 0,
            startupInProgress = false,
            startupFailures = 0,
            lastFailureAt = 0,
            emergency = false,
            updatedAt = now,
        )
        upsertMetadata(
            current.copy(
                startupInProgress = state.inProgress,
                startupFailures = state.failures,
                lastFailureAt = state.lastFailureAt,
                emergency = state.emergency,
                updatedAt = now,
            ),
        )
    }

    @Transaction
    fun replaceSnapshot(apps: List<AppSnapshotEntity>, now: Long): Long {
        val current = metadata() ?: MetadataEntity(
            generation = 0,
            startupInProgress = false,
            startupFailures = 0,
            lastFailureAt = 0,
            emergency = false,
            updatedAt = now,
        )
        val generation = current.generation + 1
        upsertMetadata(current.copy(generation = generation, updatedAt = now))
        clearSnapshot()
        insertSnapshot(apps.map { it.copy(generation = generation) })
        return generation
    }
}

class RoomStartupStore(private val db: CenixDatabase) : StartupStore {
    override fun load(): StartupState {
        val metadata = db.dao().metadata() ?: return StartupState()
        return StartupState(
            inProgress = metadata.startupInProgress,
            failures = metadata.startupFailures,
            lastFailureAt = metadata.lastFailureAt,
            emergency = metadata.emergency,
        )
    }

    override fun save(state: StartupState) {
        db.dao().saveStartup(state, System.currentTimeMillis())
    }
}

@Database(
    entities = [MetadataEntity::class, AppSnapshotEntity::class],
    version = CenixDatabase.VERSION,
    exportSchema = true,
)
abstract class CenixDatabase : RoomDatabase() {
    abstract fun dao(): CenixDao

    companion object {
        const val NAME = "cenix.db"
        const val VERSION = 1

        fun open(context: Context): CenixDatabase =
            Room.databaseBuilder(context.applicationContext, CenixDatabase::class.java, NAME)
                .allowMainThreadQueries()
                .build()
                .also { it.ensureSeed() }
    }

    fun ensureSeed() {
        if (dao().metadata() == null) {
            dao().upsertMetadata(
                MetadataEntity(
                    generation = 0,
                    startupInProgress = false,
                    startupFailures = 0,
                    lastFailureAt = 0,
                    emergency = false,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }
}
