package com.caniko.cenix

import com.caniko.cenix.uniffi.App
import com.caniko.cenix.uniffi.filterAndOrderApps

class NativeAppFilter : AppFilter {
    override fun filter(
        apps: List<LaunchableApp>,
        query: String,
        visibleProfiles: Set<Long>,
    ): List<LaunchableApp> {
        val ids = filterAndOrderApps(
            apps.map { app ->
                App(
                    `package` = app.packageName,
                    `class` = app.className,
                    profileId = app.profileId.toULong(),
                    label = app.label,
                )
            },
            query,
            visibleProfiles.map { it.toULong() },
        )
        val index = apps.associateBy { Triple(it.packageName, it.className, it.profileId) }
        return ids.mapNotNull { id ->
            index[Triple(id.`package`, id.`class`, id.profileId.toLong())]
        }
    }
}
