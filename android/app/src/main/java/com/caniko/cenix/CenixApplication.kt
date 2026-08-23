package com.caniko.cenix

import android.app.Application
import com.caniko.cenix.db.CenixDatabase
import com.caniko.cenix.db.RoomStartupStore

class CenixApplication : Application() {
    lateinit var crashLoop: CrashLoopGuard
        private set
    var database: CenixDatabase? = null
        private set
    var emergency = false
    var filterFactory: () -> AppFilter = {
        Class.forName("com.caniko.cenix.NativeAppFilter").getDeclaredConstructor().newInstance() as AppFilter
    }

    override fun onCreate() {
        super.onCreate()
        database = openDatabase()
        val store = database?.let(::RoomStartupStore) ?: MemoryStartupStore()
        crashLoop = CrashLoopGuard(store)
        if (database == null || !crashLoop.beginStartup() || crashLoop.isEmergency()) {
            emergency = true
        }
        if (emergency) {
            crashLoop.requestEmergency()
        }
    }

    fun activeFilter(): AppFilter {
        if (emergency) return EmergencyAppFilter
        return try {
            filterFactory()
        } catch (_: Throwable) {
            requestEmergency()
            EmergencyAppFilter
        }
    }

    fun markHealthy() {
        crashLoop.markHealthy()
    }

    fun requestEmergency() {
        emergency = true
        crashLoop.requestEmergency()
    }

    fun retryNative(): Boolean {
        return try {
            filterFactory()
            crashLoop.clearEmergency()
            emergency = false
            crashLoop.markHealthy()
            true
        } catch (_: Throwable) {
            requestEmergency()
            false
        }
    }

    fun resetLocalState() {
        database?.close()
        deleteDatabase(CenixDatabase.NAME)
        database = openDatabase()
        val store = database?.let(::RoomStartupStore) ?: MemoryStartupStore()
        crashLoop = CrashLoopGuard(store)
        emergency = database == null || !crashLoop.beginStartup()
        if (emergency) {
            crashLoop.requestEmergency()
        }
    }

    private fun openDatabase(): CenixDatabase? = try {
        CenixDatabase.open(this)
    } catch (_: Throwable) {
        null
    }
}
