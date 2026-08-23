package com.caniko.cenix

fun interface AppFilter {
    fun filter(apps: List<LaunchableApp>, query: String, visibleProfiles: Set<Long>): List<LaunchableApp>
}

object EmergencyAppFilter : AppFilter {
    override fun filter(
        apps: List<LaunchableApp>,
        query: String,
        visibleProfiles: Set<Long>,
    ): List<LaunchableApp> = EmergencyFilter.filter(apps, query, visibleProfiles)
}
