package com.caniko.cenix

import android.app.Application
import android.app.backup.BackupManager
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import androidx.room.InvalidationTracker
import com.caniko.cenix.db.CenixDatabase
import com.caniko.cenix.db.RoomStartupStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CenixApplication : Application() {
    lateinit var crashLoop: CrashLoopGuard
        private set
    var database: CenixDatabase? = null
        private set
    @Volatile
    var emergency = false
    var filterFactory: () -> AppFilter = {
        Class.forName("com.caniko.cenix.NativeAppFilter").getDeclaredConstructor().newInstance() as AppFilter
    }
    private val ready = CountDownLatch(1)
    private val backupHandler = Handler(Looper.getMainLooper())
    private val notifyBackup = Runnable { BackupManager(this).dataChanged() }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        CenixLog.redact = BuildConfig.REDACT_LOGS
        DiagnosticStore.init(filesDir)
        CenixLog.event(
            EventId.BUILD_IDENTITY,
            Severity.INFO,
            mapOf("commit" to BuildConfig.GIT_COMMIT, "type" to BuildConfig.BUILD_TYPE),
        )
        crashLoop = CrashLoopGuard(MemoryStartupStore())
        CenixExecutors.io {
            try {
                database = openDatabase()
                val store = database?.let(::RoomStartupStore) ?: MemoryStartupStore()
                crashLoop = CrashLoopGuard(store)
                if (database == null || !crashLoop.beginStartup()) {
                    emergency = true
                    CenixLog.event(EventId.EMERGENCY, Severity.ERROR, mapOf("reason" to "startup"))
                }
                CenixLog.event(
                    EventId.ROOM_OPEN,
                    Severity.INFO,
                    mapOf("ok" to (database != null).toString(), "schema" to CenixDatabase.VERSION.toString()),
                )
                try {
                    Class.forName("com.caniko.cenix.NativeDiagnostics")
                        .getDeclaredMethod("install")
                        .invoke(null)
                } catch (_: Throwable) {
                    emergency = true
                }
                CenixLog.event(EventId.STARTUP, Severity.INFO, mapOf("emergency" to emergency.toString()))
            } finally {
                ready.countDown()
            }
        }
    }

    fun awaitReady(timeoutMs: Long = 10_000): Boolean = ready.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun activeFilter(): AppFilter {
        if (emergency) return EmergencyAppFilter
        return try {
            loadNative()
        } catch (_: Throwable) {
            requestEmergency()
            EmergencyAppFilter
        }
    }

    fun markHealthy() {
        crashLoop.markHealthy()
    }

    fun scheduleBackup() {
        backupHandler.removeCallbacks(notifyBackup)
        backupHandler.postDelayed(notifyBackup, 2_000)
    }

    fun requestEmergency() {
        emergency = true
        crashLoop.requestEmergency()
        CenixLog.event(EventId.EMERGENCY, Severity.ERROR, mapOf("reason" to "requested"))
    }

    fun retryNative(): Boolean {
        return try {
            loadNative()
            crashLoop.clearEmergency()
            emergency = false
            crashLoop.markHealthy()
            CenixLog.event(EventId.NATIVE_INIT, Severity.INFO, mapOf("result" to "ok"))
            true
        } catch (_: Throwable) {
            requestEmergency()
            false
        }
    }

    private fun loadNative(): AppFilter {
        val filter = filterFactory()
        filter.filter(emptyList(), "", emptySet())
        val panicked = try {
            Class.forName("com.caniko.cenix.NativeDiagnostics")
                .getDeclaredMethod("panicked")
                .invoke(null) as Boolean
        } catch (_: Throwable) {
            false
        }
        if (panicked) throw IllegalStateException("native panicked")
        return filter
    }

    fun resetLocalState() {
        NotificationDotStore.replace(emptyMap())
        CenixBackupAgent.artifact(this).delete()
        CenixBackupAgent.restoredArtifact(this).delete()
        database?.close()
        deleteDatabase(CenixDatabase.NAME)
        database = openDatabase()
        val store = database?.let(::RoomStartupStore) ?: MemoryStartupStore()
        crashLoop = CrashLoopGuard(store)
        emergency = database == null || !crashLoop.beginStartup()
        DiagnosticStore.reset()
        scheduleBackup()
        CenixLog.event(EventId.RESET, Severity.INFO, mapOf("emergency" to emergency.toString()))
    }

    private fun openDatabase(): CenixDatabase? = try {
        CenixDatabase.open(this).also(::observeBackupChanges)
    } catch (_: Throwable) {
        null
    }

    private fun observeBackupChanges(db: CenixDatabase) {
        db.invalidationTracker.addObserver(
            object : InvalidationTracker.Observer(
                "workspace_metadata",
                "workspace_pages",
                "workspace_items",
                "workspace_applications",
                "workspace_shortcuts",
                "workspace_widgets",
                "workspace_folders",
                "folder_members",
                "launcher_settings",
            ) {
                override fun onInvalidated(tables: Set<String>) = scheduleBackup()
            },
        )
    }

    fun recoverSystemRestore() {
        val db = database ?: return
        try {
            CenixBackupAgent.stageRestoredArtifact(this, db)
            if (BackupRepository(db).recoverSystem(this)) {
                NotificationDotStore.setEnabled(checkNotNull(db.dao().launcherSettings()).notificationDots)
                CenixLog.event(EventId.BACKUP_RESTORE, Severity.INFO, mapOf("result" to "applied"))
            }
        } catch (error: Exception) {
            CenixLog.event(EventId.BACKUP_RESTORE, Severity.WARN, mapOf("category" to error.javaClass.simpleName))
        }
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .penaltyLog()
                .build(),
        )
    }
}
