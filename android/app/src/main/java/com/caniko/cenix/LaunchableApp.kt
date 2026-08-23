package com.caniko.cenix

import android.content.pm.LauncherActivityInfo
import android.graphics.drawable.Drawable
import android.os.UserHandle

data class LaunchableApp(
    val packageName: String,
    val className: String,
    val profileId: Long,
    val label: String,
    val normalizedLabel: String,
    val user: UserHandle,
    val icon: Drawable?,
) {
    companion object {
        fun from(info: LauncherActivityInfo): LaunchableApp {
            val label = info.label?.toString().orEmpty()
            return LaunchableApp(
                packageName = info.componentName.packageName,
                className = info.componentName.className,
                profileId = info.user.hashCode().toLong(),
                label = label,
                normalizedLabel = EmergencyFilter.normalize(label),
                user = info.user,
                icon = info.getBadgedIcon(0),
            )
        }
    }
}
