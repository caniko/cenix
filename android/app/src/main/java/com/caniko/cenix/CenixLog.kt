package com.caniko.cenix

enum class EventId {
    STARTUP,
    HOME_ROLE,
    NATIVE_INIT,
    ROOM_OPEN,
    CATALOG_REFRESH,
    PACKAGE_CALLBACK,
    PROFILE_DISCOVERY,
    PROFILE_AVAILABLE,
    PROFILE_UNAVAILABLE,
    PROFILE_QUIET,
    PROFILE_ACTIVE,
    PRIVATE_LOCKED,
    PRIVATE_UNLOCK_REQUEST,
    PROFILE_UI_INVALIDATED,
    PROFILE_OPERATION_RESULT,
    WORKSPACE_TX,
    WORKSPACE_SNAPSHOT,
    WORKSPACE_COMMAND,
    WORKSPACE_TRANSITION,
    ROOM_COMMIT,
    ROOM_ROLLBACK,
    DRAG_CANCEL,
    FOLDER_OPEN,
    FOLDER_CLOSE,
    CONTEXT_POPUP_OPEN,
    CONTEXT_POPUP_CLOSE,
    SHORTCUT_QUERY,
    SHORTCUT_LAUNCH,
    SHORTCUT_PIN,
    SHORTCUT_RECONCILE,
    PLATFORM_ACTION,
    TRANSITION_REJECTED,
    SHELL_TRANSITION,
    MIGRATION,
    EMERGENCY,
    RESET,
    BUILD_IDENTITY,
}

enum class Severity { INFO, WARN, ERROR }

object CenixLog {
    @Volatile
    var redact: Boolean = true

    private val sensitive = listOf(
        "package", "class", "label", "query", "profile", "component", "title",
        "serial", "user", "shortcut", "widget", "provider", "session", "folder", "appwidget",
    )

    fun event(id: EventId, severity: Severity, fields: Map<String, String> = emptyMap()) {
        val line = format(id, severity, fields)
        android.util.Log.println(priority(severity), "cenix", line)
        DiagnosticStore.append(line)
    }

    fun format(id: EventId, severity: Severity, fields: Map<String, String>): String {
        val body = fields.entries.joinToString(" ") { (key, value) ->
            "$key=${sanitize(key, value)}"
        }
        return "${severity.name} ${id.name} $body".trim()
    }

    fun sanitize(key: String, value: String): String {
        if (!redact) return value
        return if (sensitive.any { key.contains(it, ignoreCase = true) }) "[redacted]" else value
    }

    private fun priority(severity: Severity): Int = when (severity) {
        Severity.INFO -> android.util.Log.INFO
        Severity.WARN -> android.util.Log.WARN
        Severity.ERROR -> android.util.Log.ERROR
    }
}
