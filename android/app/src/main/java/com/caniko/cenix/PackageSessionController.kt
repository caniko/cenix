package com.caniko.cenix

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.drawable.BitmapDrawable
import android.os.UserHandle
import com.caniko.cenix.db.ApplicationItemEntity
import com.caniko.cenix.uniffi.ProfileAccess
import java.util.concurrent.ConcurrentHashMap

enum class PackageState {
    READY,
    INSTALLING,
    UPDATING,
    SUSPENDED,
    DISABLED,
    ARCHIVED,
    TEMPORARILY_UNAVAILABLE,
}

data class PackageKey(val packageName: String, val profileId: Long)

private data class SessionDisplay(
    val id: Int,
    val key: PackageKey,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
    val progress: Int,
    val user: UserHandle,
)

class PackageSessionController(
    private val context: Context,
    private val profiles: ProfileController,
    private val onChanged: (String) -> Unit,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val packageManager = context.packageManager
    private val retained = ConcurrentHashMap<PackageKey, List<LaunchableApp>>()
    private val overrides = ConcurrentHashMap<PackageKey, PackageState>()
    private val progresses = ConcurrentHashMap<PackageKey, Int>()
    private val sessions = ConcurrentHashMap<Int, SessionDisplay>()

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = clear(packageName, user, "add")
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            key(packageName, user)?.let {
                overrides.remove(it)
                progresses.remove(it)
                retained.remove(it)
            }
            onChanged("remove")
        }
        override fun onPackageChanged(packageName: String, user: UserHandle) {
            key(packageName, user)?.let { key ->
                overrides[key] = applicationState(packageName, user)
            }
            onChanged("change")
        }
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
            packageNames.forEach { clear(it, user, "available", notify = false) }
            onChanged("available")
        }
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
            packageNames.forEach { packageName ->
                key(packageName, user)?.let { overrides[it] = PackageState.TEMPORARILY_UNAVAILABLE }
            }
            onChanged("unavailable")
        }
        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) {
            packageNames.forEach { packageName -> key(packageName, user)?.let { overrides[it] = PackageState.SUSPENDED } }
            onChanged("suspended")
        }
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) {
            packageNames.forEach { clear(it, user, "unsuspended", notify = false) }
            onChanged("unsuspended")
        }
        override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) {
            onChanged("shortcuts")
        }
        override fun onPackageLoadingProgressChanged(packageName: String, user: UserHandle, progress: Float) {
            key(packageName, user)?.let {
                overrides[it] = PackageState.UPDATING
                progresses[it] = (progress.coerceIn(0f, 1f) * 100).toInt()
            }
            onChanged("loading")
        }
    }

    private val sessionCallback = object : PackageInstaller.SessionCallback() {
        override fun onCreated(sessionId: Int) = refreshSession(sessionId)
        override fun onBadgingChanged(sessionId: Int) = refreshSession(sessionId)
        override fun onActiveChanged(sessionId: Int, active: Boolean) = Unit
        override fun onProgressChanged(sessionId: Int, progress: Float) = refreshSession(sessionId)
        override fun onFinished(sessionId: Int, success: Boolean) {
            sessions.remove(sessionId)
            onChanged(if (success) "session-finished" else "session-failed")
        }
    }

    fun start() {
        launcherApps.registerCallback(packageCallback)
        launcherApps.registerPackageInstallerSessionCallback(context.mainExecutor, sessionCallback)
        launcherApps.allPackageInstallerSessions.forEach(::rememberSession)
    }

    fun stop() {
        launcherApps.unregisterCallback(packageCallback)
        launcherApps.unregisterPackageInstallerSessionCallback(sessionCallback)
    }

    fun decorate(loaded: List<LaunchableApp>, durable: List<ApplicationItemEntity>): List<LaunchableApp> {
        launcherApps.allPackageInstallerSessions.forEach(::rememberSession)
        val loadedComponents = loaded.map { Triple(it.packageName, it.className, it.profileId) }.toSet()
        val preserved = durable.mapNotNull { row ->
            if (Triple(row.packageName, row.className, row.profileId) in loadedComponents) return@mapNotNull null
            val profile = profiles.profile(row.profileId)
                ?.takeIf { it.descriptor.access == ProfileAccess.AVAILABLE } ?: return@mapNotNull null
            val key = PackageKey(row.packageName, row.profileId)
            val info = try {
                launcherApps.getApplicationInfo(row.packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES, profile.user)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            val state = overrides[key] ?: when {
                info == null -> return@mapNotNull null
                info.isArchived -> PackageState.ARCHIVED
                !info.enabled -> PackageState.DISABLED
                info.flags and ApplicationInfo.FLAG_SUSPENDED != 0 -> PackageState.SUSPENDED
                info.flags and ApplicationInfo.FLAG_INSTALLED == 0 -> PackageState.TEMPORARILY_UNAVAILABLE
                else -> PackageState.DISABLED
            }
            val label = info?.loadLabel(packageManager)?.toString()?.ifBlank { row.packageName } ?: row.packageName
            LaunchableApp(
                row.packageName,
                row.className,
                row.profileId,
                profile.descriptor.kind,
                label,
                EmergencyFilter.normalize(label),
                profile.user,
                info?.loadIcon(packageManager)?.let { packageManager.getUserBadgedIcon(it, profile.user) },
                state,
            )
        }
        val known = loaded + preserved
        known.groupBy { PackageKey(it.packageName, it.profileId) }.forEach { (key, apps) -> retained[key] = apps }
        val installedKeys = loaded.map { PackageKey(it.packageName, it.profileId) }.toSet()
        val knownKeys = known.map { PackageKey(it.packageName, it.profileId) }.toSet()
        val result = known.map { app ->
            val key = PackageKey(app.packageName, app.profileId)
            app.copy(packageState = overrides[key] ?: app.packageState, installProgress = progresses[key])
        }.toMutableList()
        overrides.filterValues { it != PackageState.READY }.forEach { (key, state) ->
            if (key !in installedKeys) retained[key].orEmpty().forEach {
                result += it.copy(packageState = state, installProgress = progresses[key])
            }
        }
        sessions.values.sortedWith(compareBy({ it.key.profileId }, { it.key.packageName }, { it.id })).forEach { session ->
            val state = if (session.key in knownKeys || retained.containsKey(session.key)) PackageState.UPDATING else PackageState.INSTALLING
            if (session.key !in knownKeys) {
                val profile = profiles.profile(session.key.profileId)?.descriptor
                    ?.takeIf { it.access == ProfileAccess.AVAILABLE } ?: return@forEach
                result += LaunchableApp(
                    packageName = session.key.packageName,
                    className = SESSION_CLASS,
                    profileId = session.key.profileId,
                    profileKind = profile.kind,
                    label = session.label,
                    normalizedLabel = EmergencyFilter.normalize(session.label),
                    user = session.user,
                    icon = session.icon,
                    packageState = state,
                    installProgress = session.progress,
                )
            } else {
                result.replaceAll { app ->
                    if (app.packageName == session.key.packageName && app.profileId == session.key.profileId) {
                        app.copy(packageState = state, installProgress = session.progress)
                    } else {
                        app
                    }
                }
            }
        }
        return result.distinctBy { Triple(it.packageName, it.className, it.profileId) }
    }

    private fun refreshSession(sessionId: Int) {
        launcherApps.allPackageInstallerSessions.firstOrNull { it.sessionId == sessionId }?.let(::rememberSession)
        onChanged("session")
    }

    private fun rememberSession(info: PackageInstaller.SessionInfo) {
        val packageName = info.appPackageName?.takeIf(String::isNotBlank) ?: return
        val user = info.user ?: return
        val installer = info.installerPackageName ?: return
        if (installer != context.packageName) {
            val trusted = try {
                launcherApps.getApplicationInfo(installer, PackageManager.MATCH_UNINSTALLED_PACKAGES, user)
                    .flags and ApplicationInfo.FLAG_SYSTEM != 0
            } catch (_: RuntimeException) {
                false
            }
            if (!trusted) return
        }
        val key = key(packageName, user) ?: return
        sessions[info.sessionId] = SessionDisplay(
            info.sessionId,
            key,
            info.appLabel?.toString()?.takeIf(String::isNotBlank) ?: packageName,
            info.appIcon?.let { BitmapDrawable(context.resources, it) },
            (info.progress.coerceIn(0f, 1f) * 100).toInt(),
            user,
        )
    }

    private fun clear(packageName: String, user: UserHandle, reason: String, notify: Boolean = true) {
        key(packageName, user)?.let {
            overrides.remove(it)
            progresses.remove(it)
        }
        if (notify) onChanged(reason)
    }

    private fun key(packageName: String, user: UserHandle): PackageKey? =
        profiles.profile(user)?.descriptor?.profileId?.toLong()?.let { PackageKey(packageName, it) }

    private fun applicationState(packageName: String, user: UserHandle): PackageState = try {
        val info = launcherApps.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES, user)
        when {
            info.isArchived -> PackageState.ARCHIVED
            !info.enabled -> PackageState.DISABLED
            info.flags and ApplicationInfo.FLAG_SUSPENDED != 0 -> PackageState.SUSPENDED
            else -> PackageState.READY
        }
    } catch (_: PackageManager.NameNotFoundException) {
        PackageState.TEMPORARILY_UNAVAILABLE
    }

    companion object {
        private const val SESSION_CLASS = "<install-session>"
    }
}
