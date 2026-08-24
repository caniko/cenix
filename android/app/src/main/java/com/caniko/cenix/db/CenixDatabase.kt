package com.caniko.cenix.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.caniko.cenix.StartupState
import com.caniko.cenix.StartupStore

@Entity(tableName = "launcher_metadata")
data class MetadataEntity(
    @PrimaryKey val singletonId: Int = 1,
    val startupInProgress: Boolean,
    val startupFailures: Int,
    val lastFailureAt: Long,
    val emergency: Boolean,
    val updatedAt: Long,
)

@Entity(
    tableName = "workspace_items",
    indices = [
        Index(value = ["screen", "cellX", "cellY"], unique = true),
        Index(value = ["packageName", "className", "profileId"], unique = true),
    ],
)
data class WorkspaceItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val screen: Int,
    val cellX: Int,
    val cellY: Int,
    val packageName: String,
    val className: String,
    val profileId: Long,
)

@Dao
interface CenixDao {
    @Query("SELECT * FROM launcher_metadata WHERE singletonId = 1")
    fun metadata(): MetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertMetadata(entity: MetadataEntity)

    @Query("SELECT * FROM workspace_items ORDER BY screen, cellY, cellX")
    fun workspaceItems(): List<WorkspaceItemEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertWorkspace(entity: WorkspaceItemEntity): Long

    @Query("DELETE FROM workspace_items WHERE packageName = :packageName AND className = :className AND profileId = :profileId")
    fun deleteWorkspace(packageName: String, className: String, profileId: Long)

    @Query("UPDATE workspace_items SET screen = :screen, cellX = :cellX, cellY = :cellY WHERE packageName = :packageName AND className = :className AND profileId = :profileId")
    fun moveWorkspace(screen: Int, cellX: Int, cellY: Int, packageName: String, className: String, profileId: Long)

    @Query("DELETE FROM workspace_items")
    fun clearWorkspace()

    @Transaction
    fun replaceWorkspace(items: List<WorkspaceItemEntity>) {
        clearWorkspace()
        items.forEach { insertWorkspace(it.copy(id = 0)) }
    }

    @Transaction
    fun saveStartup(state: StartupState, now: Long) {
        upsertMetadata(
            MetadataEntity(
                startupInProgress = state.inProgress,
                startupFailures = state.failures,
                lastFailureAt = state.lastFailureAt,
                emergency = state.emergency,
                updatedAt = now,
            ),
        )
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
    entities = [MetadataEntity::class, WorkspaceItemEntity::class],
    version = CenixDatabase.VERSION,
    exportSchema = true,
)
abstract class CenixDatabase : RoomDatabase() {
    abstract fun dao(): CenixDao

    companion object {
        const val NAME = "cenix.db"
        const val VERSION = 2

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workspace_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `screen` INTEGER NOT NULL, `cellX` INTEGER NOT NULL, `cellY` INTEGER NOT NULL, `packageName` TEXT NOT NULL, `className` TEXT NOT NULL, `profileId` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_items_screen_cellX_cellY` ON `workspace_items` (`screen`, `cellX`, `cellY`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_items_packageName_className_profileId` ON `workspace_items` (`packageName`, `className`, `profileId`)",
                )
            }
        }

        fun open(context: Context): CenixDatabase {
            val builder = Room.databaseBuilder(context.applicationContext, CenixDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
            if (android.os.Build.FINGERPRINT == "robolectric") {
                builder.allowMainThreadQueries()
            }
            return builder.build().also { it.ensureSeed() }
        }
    }

    fun ensureSeed() {
        if (dao().metadata() == null) {
            dao().upsertMetadata(
                MetadataEntity(
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
