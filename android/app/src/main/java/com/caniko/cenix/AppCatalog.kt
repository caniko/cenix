package com.caniko.cenix

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.os.UserManager

class AppCatalog(context: Context) {
    private val selfPackage = context.packageName
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    fun profiles(): List<UserHandle> = launcherApps.profiles

    fun serial(user: UserHandle): Long = userManager.getSerialNumberForUser(user)

    fun userForSerial(serial: Long): UserHandle? = userManager.getUserForSerialNumber(serial)

    fun visibleProfiles(): Set<Long> = profiles().map(::serial).toSet()

    fun load(): List<LaunchableApp> =
        profiles().flatMap { user ->
            launcherApps.getActivityList(null, user)
                .filter { it.componentName.packageName != selfPackage }
                .map { LaunchableApp.from(it, serial(user)) }
        }

    fun register(callback: LauncherApps.Callback) {
        launcherApps.registerCallback(callback)
    }

    fun unregister(callback: LauncherApps.Callback) {
        launcherApps.unregisterCallback(callback)
    }
}
