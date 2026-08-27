package com.caniko.cenix

import android.content.pm.LauncherActivityInfo
import android.graphics.drawable.Drawable
import android.os.UserHandle
import com.caniko.cenix.uniffi.ProfileDescriptor
import com.caniko.cenix.uniffi.ProfileKind

data class LaunchableApp(
    val packageName: String,
    val className: String,
    val profileId: Long,
    val profileKind: ProfileKind,
    val label: String,
    val normalizedLabel: String,
    val user: UserHandle?,
    val icon: Drawable?,
    val packageState: PackageState = PackageState.READY,
    val installProgress: Int? = null,
    val baseIcon: Drawable? = icon,
) {
    val canLaunch: Boolean get() = packageState == PackageState.READY || packageState == PackageState.ARCHIVED
    val canPlace: Boolean get() = packageState == PackageState.READY

    companion object {
        fun from(info: LauncherActivityInfo, profile: ProfileDescriptor): LaunchableApp {
            val label = info.label?.toString().orEmpty()
            return LaunchableApp(
                packageName = info.componentName.packageName,
                className = info.componentName.className,
                profileId = profile.profileId.toLong(),
                profileKind = profile.kind,
                label = label,
                normalizedLabel = EmergencyFilter.normalize(label),
                user = info.user,
                icon = info.getBadgedIcon(0),
                baseIcon = info.getIcon(0),
                packageState = when {
                    info.applicationInfo.isArchived -> PackageState.ARCHIVED
                    !info.applicationInfo.enabled -> PackageState.DISABLED
                    info.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED != 0 -> PackageState.SUSPENDED
                    else -> PackageState.READY
                },
            )
        }
    }
}
