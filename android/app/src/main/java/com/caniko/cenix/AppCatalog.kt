package com.caniko.cenix

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserManager

class AppCatalog(context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    fun visibleProfiles(): Set<Long> {
        val users = userManager.userProfiles ?: listOf(Process.myUserHandle())
        return users.map { it.hashCode().toLong() }.toSet()
    }

    fun load(): List<LaunchableApp> {
        val users = userManager.userProfiles ?: listOf(Process.myUserHandle())
        return users.flatMap { user ->
            launcherApps.getActivityList(null, user).map(LaunchableApp::from)
        }
    }
}
