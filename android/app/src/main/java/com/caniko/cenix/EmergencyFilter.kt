package com.caniko.cenix

object EmergencyFilter {
    fun filter(apps: List<LaunchableApp>, query: String, visibleProfiles: Set<Long>): List<LaunchableApp> {
        val normalized = normalize(query)
        val visible = apps.filter { it.profileId in visibleProfiles }
        if (normalized.isEmpty()) {
            return visible.sortedWith(compareBy({ it.normalizedLabel }, { it.packageName }, { it.className }, { it.profileId }))
        }
        return visible
            .mapNotNull { app -> rank(app.normalizedLabel, normalized)?.let { it to app } }
            .sortedWith(
                compareBy(
                    { it.first },
                    { it.second.normalizedLabel },
                    { it.second.packageName },
                    { it.second.className },
                    { it.second.profileId },
                ),
            )
            .map { it.second }
    }

    private fun rank(label: String, query: String): Int? = when {
        label == query -> 0
        label.startsWith(query) -> 1
        label.split(' ').any { it.startsWith(query) } -> 2
        label.contains(query) -> 3
        else -> null
    }

    fun normalize(value: String): String =
        value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").lowercase()
}
