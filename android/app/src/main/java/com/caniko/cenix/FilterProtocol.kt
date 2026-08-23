package com.caniko.cenix

import org.json.JSONArray
import org.json.JSONObject

data class ComponentIdentity(
    val packageName: String,
    val className: String,
    val profileId: Long,
)

data class FilterOutcome(
    val ok: Boolean,
    val requestId: String,
    val matches: List<ComponentIdentity>,
    val errorCode: String?,
)

object FilterProtocol {
    const val VERSION = 1

    fun encodeRequest(requestId: String, query: String, profiles: Set<Long>, apps: List<LaunchableApp>): ByteArray {
        val applications = JSONArray()
        for (app in apps) {
            applications.put(
                JSONObject()
                    .put("package", app.packageName)
                    .put("class", app.className)
                    .put("profile_id", app.profileId)
                    .put("label", app.label),
            )
        }
        val profilesJson = JSONArray()
        for (id in profiles) profilesJson.put(id)
        return JSONObject()
            .put("protocol_version", VERSION)
            .put("request_id", requestId)
            .put("query", query)
            .put("visible_profile_ids", profilesJson)
            .put("applications", applications)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun decodeResponse(bytes: ByteArray?): FilterOutcome {
        if (bytes == null) {
            return FilterOutcome(false, "", emptyList(), "NULL")
        }
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val error = json.optJSONObject("error")
        val matchesJson = json.optJSONArray("matches")
        val matches = mutableListOf<ComponentIdentity>()
        if (matchesJson != null) {
            for (i in 0 until matchesJson.length()) {
                val item = matchesJson.getJSONObject(i)
                matches.add(
                    ComponentIdentity(
                        packageName = item.getString("package"),
                        className = item.getString("class"),
                        profileId = item.getLong("profile_id"),
                    ),
                )
            }
        }
        return FilterOutcome(
            ok = json.optBoolean("ok", false),
            requestId = json.optString("request_id"),
            matches = matches,
            errorCode = error?.optString("code"),
        )
    }
}
