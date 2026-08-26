package com.caniko.cenix

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import com.caniko.cenix.uniffi.ProfileAccess

class AppCatalog(context: Context, private val profiles: ProfileController) {
    private val selfPackage = context.packageName
    private val launcherApps = context.getSystemService(LauncherApps::class.java)

    fun visibleProfiles(): Set<Long> = profiles.profiles()
        .filter { it.descriptor.access == ProfileAccess.AVAILABLE }
        .map { it.descriptor.profileId.toLong() }
        .toSet()

    fun userForSerial(serial: Long): UserHandle? = profiles.userForSerial(serial)

    fun load(snapshot: List<AndroidProfile> = profiles.profiles()): List<LaunchableApp> =
        snapshot.filter { it.descriptor.access == ProfileAccess.AVAILABLE }.flatMap { profile ->
            try {
                launcherApps.getActivityList(null, profile.user)
                    .filter { it.componentName.packageName != selfPackage }
                    .map { LaunchableApp.from(it, profile.descriptor) }
            } catch (_: RuntimeException) {
                emptyList()
            }
        }

    fun register(callback: LauncherApps.Callback) {
        launcherApps.registerCallback(callback)
    }

    fun unregister(callback: LauncherApps.Callback) {
        launcherApps.unregisterCallback(callback)
    }
}
