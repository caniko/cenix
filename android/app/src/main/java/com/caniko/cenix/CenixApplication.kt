package com.caniko.cenix

import android.app.Application
import com.caniko.cenix.db.CenixDatabase
import java.io.File

class CenixApplication : Application() {
    lateinit var crashLoop: CrashLoopGuard
        private set
    var database: CenixDatabase? = null
        private set
    var emergency = false

    override fun onCreate() {
        super.onCreate()
        crashLoop = CrashLoopGuard(PrefStore(getSharedPreferences("cenix", MODE_PRIVATE)))
        if (!crashLoop.beginStartup()) {
            emergency = true
        }
        database = openDatabase()
        if (database == null) {
            emergency = true
        } else if (database!!.isEmergency()) {
            emergency = true
        }
        if (!NativeBridge.loaded) {
            emergency = true
        }
        if (emergency) {
            database?.setEmergency(true)
        }
    }

    fun markHealthy() {
        crashLoop.markHealthy()
        database?.let { db ->
            val generation = db.dao().metadata()?.generation ?: 0
            db.dao().markHealthy(generation, System.currentTimeMillis())
        }
    }

    fun requestEmergency() {
        emergency = true
        crashLoop.requestEmergency()
        database?.setEmergency(true)
    }

    fun retryNative(): Boolean {
        if (!NativeBridge.loaded) return false
        crashLoop.clearUserEmergency()
        database?.setEmergency(false)
        emergency = false
        crashLoop.markHealthy()
        return true
    }

    fun resetLocalState() {
        database?.close()
        deleteDatabase(CenixDatabase.NAME)
        getSharedPreferences("cenix", MODE_PRIVATE).edit().clear().apply()
        crashLoop = CrashLoopGuard(PrefStore(getSharedPreferences("cenix", MODE_PRIVATE)))
        database = openDatabase()
        emergency = !NativeBridge.loaded
        if (!crashLoop.beginStartup()) {
            emergency = true
        }
    }

    private fun openDatabase(): CenixDatabase? {
        val file = getDatabasePath(CenixDatabase.NAME)
        val backup = File(file.path + ".bak")
        return try {
            CenixDatabase.open(this)
        } catch (_: Throwable) {
            if (file.exists()) {
                file.copyTo(backup, overwrite = true)
            }
            try {
                deleteDatabase(CenixDatabase.NAME)
                CenixDatabase.open(this)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
