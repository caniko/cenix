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

@Entity(tableName = "workspace_metadata")
data class WorkspaceMetadataEntity(
    @PrimaryKey val singletonId: Int = 1,
    val generation: Long,
    val cols: Int,
    val rows: Int,
    val hotseatCols: Int,
    val nextItemId: Long = 1,
    val nextPageId: Long = 2,
)

@Entity(
    tableName = "workspace_pages",
    indices = [Index(value = ["rank"], unique = true)],
)
data class WorkspacePageEntity(
    @PrimaryKey val pageId: Long,
    val rank: Int,
)

@Entity(
    tableName = "workspace_items",
    indices = [
        Index(value = ["containerKind", "containerId", "cellX", "cellY"], unique = true),
        Index(value = ["packageName", "className", "profileId"], unique = true),
    ],
)
data class WorkspaceItemEntity(
    @PrimaryKey val id: Long,
    val containerKind: String,
    val containerId: Long,
    val cellX: Int,
    val cellY: Int,
    val spanX: Int = 1,
    val spanY: Int = 1,
    val itemKind: String = ITEM_APPLICATION,
    val packageName: String,
    val className: String,
    val profileId: Long,
) {
    companion object {
        const val CONTAINER_WORKSPACE = "WORKSPACE"
        const val CONTAINER_HOTSEAT = "HOTSEAT"
        const val ITEM_APPLICATION = "APPLICATION"
    }
}

data class WorkspaceRows(
    val metadata: WorkspaceMetadataEntity,
    val pages: List<WorkspacePageEntity>,
    val items: List<WorkspaceItemEntity>,
)

class StaleWorkspaceGeneration : IllegalStateException("stale workspace generation")
class InvalidWorkspaceTransition : IllegalStateException("invalid workspace transition")

@Dao
interface CenixDao {
    @Query("SELECT * FROM launcher_metadata WHERE singletonId = 1")
    fun metadata(): MetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertMetadata(entity: MetadataEntity)

    @Query("SELECT * FROM workspace_metadata WHERE singletonId = 1")
    fun workspaceMetadata(): WorkspaceMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertWorkspaceMetadata(entity: WorkspaceMetadataEntity)

    @Query("SELECT * FROM workspace_pages ORDER BY rank")
    fun workspacePages(): List<WorkspacePageEntity>

    @Query("SELECT * FROM workspace_items ORDER BY containerKind, containerId, cellY, cellX")
    fun workspaceItems(): List<WorkspaceItemEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertPages(entities: List<WorkspacePageEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertWorkspace(entities: List<WorkspaceItemEntity>)

    @Query("DELETE FROM workspace_pages")
    fun clearPages()

    @Query("DELETE FROM workspace_items")
    fun clearWorkspace()

    @Query(
        "UPDATE workspace_metadata SET generation = :nextGeneration, cols = :cols, rows = :rows, hotseatCols = :hotseatCols, " +
            "nextItemId = :nextItemId, nextPageId = :nextPageId " +
            "WHERE singletonId = 1 AND generation = :expectedGeneration",
    )
    fun advanceGeneration(
        expectedGeneration: Long,
        nextGeneration: Long,
        cols: Int,
        rows: Int,
        hotseatCols: Int,
        nextItemId: Long,
        nextPageId: Long,
    ): Int

    @Transaction
    fun workspaceState(): WorkspaceRows = WorkspaceRows(
        metadata = checkNotNull(workspaceMetadata()),
        pages = workspacePages(),
        items = workspaceItems(),
    )

    @Transaction
    fun commitWorkspace(
        expectedGeneration: Long,
        metadata: WorkspaceMetadataEntity,
        pages: List<WorkspacePageEntity>,
        items: List<WorkspaceItemEntity>,
    ) {
        val currentMetadata = checkNotNull(workspaceMetadata())
        if (currentMetadata.generation != expectedGeneration) throw StaleWorkspaceGeneration()
        if (metadata.generation == expectedGeneration) {
            if (metadata != currentMetadata || pages != workspacePages() || items != workspaceItems()) {
                throw InvalidWorkspaceTransition()
            }
            return
        }
        if (metadata.generation != expectedGeneration + 1) throw InvalidWorkspaceTransition()
        if (
            advanceGeneration(
                expectedGeneration,
                metadata.generation,
                metadata.cols,
                metadata.rows,
                metadata.hotseatCols,
                metadata.nextItemId,
                metadata.nextPageId,
            ) != 1
        ) {
            throw StaleWorkspaceGeneration()
        }
        clearWorkspace()
        clearPages()
        insertPages(pages)
        if (items.isNotEmpty()) insertWorkspace(items)
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
    entities = [MetadataEntity::class, WorkspaceMetadataEntity::class, WorkspacePageEntity::class, WorkspaceItemEntity::class],
    version = CenixDatabase.VERSION,
    exportSchema = true,
)
abstract class CenixDatabase : RoomDatabase() {
    abstract fun dao(): CenixDao

    companion object {
        const val NAME = "cenix.db"
        const val VERSION = 3

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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `workspace_items` RENAME TO `workspace_items_v2`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workspace_metadata` (`singletonId` INTEGER NOT NULL, `generation` INTEGER NOT NULL, `cols` INTEGER NOT NULL, `rows` INTEGER NOT NULL, `hotseatCols` INTEGER NOT NULL, `nextItemId` INTEGER NOT NULL, `nextPageId` INTEGER NOT NULL, PRIMARY KEY(`singletonId`))",
                )
                db.execSQL(
                    "INSERT INTO `workspace_metadata` SELECT 1, 0, 4, 5, 4, COALESCE(MAX(`id`) + 1, 1), CASE WHEN EXISTS(SELECT 1 FROM `workspace_items_v2` WHERE `screen` = 1) THEN 3 ELSE 2 END FROM `workspace_items_v2`",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workspace_pages` (`pageId` INTEGER NOT NULL, `rank` INTEGER NOT NULL, PRIMARY KEY(`pageId`))",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_pages_rank` ON `workspace_pages` (`rank`)")
                db.execSQL("INSERT INTO `workspace_pages` VALUES (1, 0)")
                db.execSQL("INSERT INTO `workspace_pages` SELECT 2, 1 WHERE EXISTS(SELECT 1 FROM `workspace_items_v2` WHERE `screen` = 1)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workspace_items` (`id` INTEGER NOT NULL, `containerKind` TEXT NOT NULL, `containerId` INTEGER NOT NULL, `cellX` INTEGER NOT NULL, `cellY` INTEGER NOT NULL, `spanX` INTEGER NOT NULL, `spanY` INTEGER NOT NULL, `itemKind` TEXT NOT NULL, `packageName` TEXT NOT NULL, `className` TEXT NOT NULL, `profileId` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "INSERT INTO `workspace_items` SELECT `id`, CASE WHEN `screen` = -1 THEN 'HOTSEAT' ELSE 'WORKSPACE' END, CASE WHEN `screen` = -1 THEN 0 ELSE `screen` + 1 END, `cellX`, `cellY`, 1, 1, 'APPLICATION', `packageName`, `className`, `profileId` FROM `workspace_items_v2` WHERE `screen` IN (-1, 0, 1)",
                )
                db.execSQL("DROP TABLE `workspace_items_v2`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_items_containerKind_containerId_cellX_cellY` ON `workspace_items` (`containerKind`, `containerId`, `cellX`, `cellY`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_workspace_items_packageName_className_profileId` ON `workspace_items` (`packageName`, `className`, `profileId`)",
                )
            }
        }

        fun open(context: Context): CenixDatabase {
            val builder = Room.databaseBuilder(context.applicationContext, CenixDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            if (android.os.Build.FINGERPRINT == "robolectric") builder.allowMainThreadQueries()
            return builder.build().also { it.ensureSeed() }
        }
    }

    @Transaction
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
        if (dao().workspaceMetadata() == null) {
            dao().upsertWorkspaceMetadata(WorkspaceMetadataEntity(generation = 0, cols = 4, rows = 5, hotseatCols = 4))
            dao().insertPages(listOf(WorkspacePageEntity(pageId = 1, rank = 0)))
        }
    }
}
