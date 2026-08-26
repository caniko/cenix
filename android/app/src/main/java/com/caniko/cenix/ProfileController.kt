package com.caniko.cenix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import com.caniko.cenix.uniffi.ProfileAccess
import com.caniko.cenix.uniffi.ProfileDescriptor
import com.caniko.cenix.uniffi.ProfileKind

data class AndroidProfile(val descriptor: ProfileDescriptor, val user: UserHandle)

data class ProfileChange(
    val profiles: List<AndroidProfile>,
    val removedProfileIds: Set<Long>,
    val newlyInaccessibleProfileIds: Set<Long>,
    val newlyAvailableProfileIds: Set<Long>,
)

internal object ProfileClassifier {
    fun descriptor(
        profileId: Long,
        owner: Boolean,
        userType: String?,
        quiet: Boolean,
        running: Boolean,
        unlocked: Boolean,
    ): ProfileDescriptor {
        val kind = when {
            owner -> ProfileKind.PERSONAL
            userType == UserManager.USER_TYPE_PROFILE_MANAGED -> ProfileKind.WORK
            userType == UserManager.USER_TYPE_PROFILE_PRIVATE -> ProfileKind.PRIVATE
            else -> ProfileKind.OTHER
        }
        val access = when {
            quiet && kind == ProfileKind.PRIVATE -> ProfileAccess.LOCKED
            quiet -> ProfileAccess.QUIET
            !running || !unlocked -> ProfileAccess.UNAVAILABLE
            else -> ProfileAccess.AVAILABLE
        }
        return ProfileDescriptor(profileId.toULong(), kind, access)
    }
}

class ProfileController(context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val owner = Process.myUserHandle()
    private var current = emptyMap<Long, AndroidProfile>()

    fun refresh(): ProfileChange {
        val next = launcherApps.profiles.mapNotNull { user ->
            val profileId = userManager.getSerialNumberForUser(user)
            if (profileId < 0) return@mapNotNull null
            val userType = try {
                launcherApps.getLauncherUserInfo(user)?.userType
            } catch (_: RuntimeException) {
                null
            }
            val quiet = try {
                userManager.isQuietModeEnabled(user)
            } catch (_: RuntimeException) {
                true
            }
            val descriptor = ProfileClassifier.descriptor(
                profileId,
                user == owner,
                userType,
                quiet,
                userManager.isUserRunning(user),
                userManager.isUserUnlocked(user),
            )
            AndroidProfile(descriptor, user)
        }.associateBy { it.descriptor.profileId.toLong() }
        val removed = current.keys - next.keys
        val inaccessible = next.filter { (profileId, profile) ->
            profile.descriptor.access != ProfileAccess.AVAILABLE &&
                current[profileId]?.descriptor?.access == ProfileAccess.AVAILABLE
        }.keys
        val available = next.filter { (profileId, profile) ->
            profile.descriptor.access == ProfileAccess.AVAILABLE &&
                current[profileId]?.descriptor?.access?.let { it != ProfileAccess.AVAILABLE } == true
        }.keys
        current = next
        return ProfileChange(next.values.sortedBy { it.descriptor.profileId }, removed, inaccessible, available)
    }

    fun profiles(): List<AndroidProfile> = current.values.sortedBy { it.descriptor.profileId }

    fun profile(profileId: Long): AndroidProfile? = current[profileId]

    fun profile(user: UserHandle): AndroidProfile? = current.values.firstOrNull { it.user == user }

    fun isAvailable(profileId: Long): Boolean =
        current[profileId]?.descriptor?.access == ProfileAccess.AVAILABLE

    fun userForSerial(profileId: Long): UserHandle? = current[profileId]?.user
        ?: userManager.getUserForSerialNumber(profileId)

    fun requestAvailable(profileId: Long, available: Boolean): Boolean {
        val user = userForSerial(profileId) ?: return false
        return try {
            userManager.requestQuietModeEnabled(!available, user)
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    fun register(context: Context, onChanged: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = onChanged()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PROFILE_ADDED)
            addAction(Intent.ACTION_PROFILE_REMOVED)
            addAction(Intent.ACTION_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_PROFILE_ACCESSIBLE)
            addAction(Intent.ACTION_PROFILE_INACCESSIBLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        return receiver
    }
}
