package com.caniko.cenix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import com.caniko.cenix.uniffi.DiscoveryInput
import com.caniko.cenix.uniffi.DiscoveryState
import com.caniko.cenix.uniffi.ProfileAccess
import com.caniko.cenix.uniffi.ProfileDescriptor
import com.caniko.cenix.uniffi.ProfileKind
import com.caniko.cenix.uniffi.ProfileKindEntry
import com.caniko.cenix.uniffi.UserTypeHint
import com.caniko.cenix.uniffi.classifyProfile
import com.caniko.cenix.uniffi.discoveryPreviousKind
import com.caniko.cenix.uniffi.reconcileProfiles
import com.caniko.cenix.uniffi.updateDiscovery

data class AndroidProfile(val descriptor: ProfileDescriptor, val user: UserHandle)

data class ProfileChange(
    val profiles: List<AndroidProfile>,
    val removedProfileIds: Set<Long>,
    val newlyInaccessibleProfileIds: Set<Long>,
    val newlyAvailableProfileIds: Set<Long>,
    // Profiles without an authoritative classification yet: callers must hide but
    // never delete their data until discovery succeeds.
    val uncertainProfileIds: Set<Long> = emptySet(),
)

internal data class ProfileDiscoveryInput(val profileId: Long, val owner: Boolean, val userType: String?)

internal data class ProfileObservationInput(
    val profileId: Long,
    val owner: Boolean,
    val userType: String?,
    val quiet: Boolean,
    val running: Boolean,
    val unlocked: Boolean,
    // False when the serial lookup failed and profileId is the last known
    // serial for this handle: hide and preserve, never remove.
    val serialResolved: Boolean,
)

internal data class ReconciledProfiles(
    val descriptors: List<ProfileDescriptor>,
    val state: ProfileDiscoveryState,
    val removedProfileIds: Set<Long>,
)

internal data class ProfileDiscoveryState(
    val authoritativeKinds: Set<Long> = emptySet(),
    val uncertain: Set<Long> = emptySet(),
)

internal object ProfileDiscovery {
    // Quarantine policy is canonical in Rust (cenix-core profiles module) and is
    // used whenever the native library is available. The pure-Kotlin fallback below
    // exists solely for omitNative builds and emergency mode (ADR 0009), which must
    // classify profiles with no .so loaded. Both paths share test vectors with the
    // Rust suite so drift is caught.
    fun update(
        state: ProfileDiscoveryState,
        inputs: List<ProfileDiscoveryInput>,
        existingIds: Set<Long>,
    ): ProfileDiscoveryState = try {
        val next = updateDiscovery(
            DiscoveryState(
                state.authoritativeKinds.map { it.toULong() },
                state.uncertain.map { it.toULong() },
            ),
            inputs.map {
                DiscoveryInput(it.profileId.toULong(), it.owner, it.userType?.let(::toHint))
            },
            existingIds.map { it.toULong() },
        )
        ProfileDiscoveryState(
            next.authoritative.map { it.toLong() }.toSet(),
            next.uncertain.map { it.toLong() }.toSet(),
        )
    } catch (_: Throwable) {
        fallbackUpdate(state, inputs, existingIds)
    }

    // One batched reconciliation owns removal and availability decisions so a
    // failed serial lookup can never become a profile removal. Canonical in
    // Rust (cenix-core profiles module); the fallback mirrors it for
    // omitNative/emergency only.
    fun reconcile(
        state: ProfileDiscoveryState,
        observations: List<ProfileObservationInput>,
        previousKinds: Map<Long, ProfileKind>,
    ): ReconciledProfiles = try {
        val reconciled = reconcileProfiles(
            DiscoveryState(
                state.authoritativeKinds.map { it.toULong() },
                state.uncertain.map { it.toULong() },
            ),
            observations.map {
                com.caniko.cenix.uniffi.ProfileObservation(
                    it.profileId.toULong(),
                    it.owner,
                    it.userType?.let(::toHint),
                    it.quiet,
                    it.running,
                    it.unlocked,
                    it.serialResolved,
                )
            },
            previousKinds.map { (id, kind) -> ProfileKindEntry(id.toULong(), kind) },
        )
        ReconciledProfiles(
            reconciled.descriptors,
            ProfileDiscoveryState(
                reconciled.state.authoritative.map { it.toLong() }.toSet(),
                reconciled.state.uncertain.map { it.toLong() }.toSet(),
            ),
            reconciled.removed.map { it.toLong() }.toSet(),
        )
    } catch (_: Throwable) {
        fallbackReconcile(state, observations, previousKinds)
    }

    internal fun fallbackReconcile(
        state: ProfileDiscoveryState,
        observations: List<ProfileObservationInput>,
        previousKinds: Map<Long, ProfileKind>,
    ): ReconciledProfiles {
        val next = fallbackUpdate(
            state,
            observations.filter { it.serialResolved }
                .map { ProfileDiscoveryInput(it.profileId, it.owner, it.userType) },
            observations.map { it.profileId }.toSet(),
        )
        val uncertain = next.uncertain.toMutableSet()
        observations.filter { !it.serialResolved && it.profileId !in next.authoritativeKinds }
            .forEach { uncertain.add(it.profileId) }
        val descriptors = observations.map { observation ->
            val previous = previousKinds[observation.profileId]
                .takeIf { next.authoritativeKinds.contains(observation.profileId) }
            // An unresolved serial must not reclassify from a partial observation.
            val userType = observation.userType.takeIf { observation.serialResolved }
            val descriptor = ProfileClassifier.fallbackDescriptor(
                observation.profileId,
                observation.owner,
                userType,
                observation.quiet,
                observation.running,
                observation.unlocked,
                previous,
            )
            // Mirror the native fail-closed clamp: flags observed alongside a
            // failed lookup never publish availability.
            if (!observation.serialResolved && descriptor.access == ProfileAccess.AVAILABLE) {
                descriptor.copy(access = ProfileAccess.UNAVAILABLE)
            } else descriptor
        }.sortedBy { it.profileId }
        val observed = observations.map { it.profileId }.toSet()
        return ReconciledProfiles(
            descriptors,
            next.copy(uncertain = uncertain),
            previousKinds.keys - observed,
        )
    }

    fun previousKind(
        state: ProfileDiscoveryState,
        currentKinds: Map<Long, ProfileKind>,
        profileId: Long,
    ): ProfileKind? = try {
        discoveryPreviousKind(
            DiscoveryState(
                state.authoritativeKinds.map { it.toULong() },
                state.uncertain.map { it.toULong() },
            ),
            currentKinds.map { (id, kind) -> ProfileKindEntry(id.toULong(), kind) },
            profileId.toULong(),
        )
    } catch (_: Throwable) {
        currentKinds[profileId].takeIf { state.authoritativeKinds.contains(profileId) }
    }

    internal fun fallbackUpdate(
        state: ProfileDiscoveryState,
        inputs: List<ProfileDiscoveryInput>,
        existingIds: Set<Long>,
    ): ProfileDiscoveryState {
        val authoritative = state.authoritativeKinds.toMutableSet()
        val uncertain = mutableSetOf<Long>()
        inputs.forEach { input ->
            if (input.userType != null || input.owner) authoritative.add(input.profileId)
            else if (!authoritative.contains(input.profileId)) uncertain.add(input.profileId)
        }
        state.uncertain.intersect(existingIds).filterNot { authoritative.contains(it) }.forEach { uncertain.add(it) }
        authoritative.retainAll(existingIds)
        uncertain.removeAll(authoritative)
        return ProfileDiscoveryState(authoritative, uncertain)
    }

    private fun toHint(userType: String): UserTypeHint = when (userType) {
        UserManager.USER_TYPE_PROFILE_MANAGED -> UserTypeHint.MANAGED
        UserManager.USER_TYPE_PROFILE_PRIVATE -> UserTypeHint.PRIVATE
        else -> UserTypeHint.OTHER
    }
}

internal object ProfileClassifier {
    // Same split as ProfileDiscovery: Rust FFI when available, pure-Kotlin
    // fallback for omitNative/emergency. See ADR 0009.
    fun descriptor(
        profileId: Long,
        owner: Boolean,
        userType: String?,
        quiet: Boolean,
        running: Boolean,
        unlocked: Boolean,
        previousKind: ProfileKind? = null,
    ): ProfileDescriptor = try {
        classifyProfile(
            profileId.toULong(),
            owner,
            userType?.let {
                when (it) {
                    UserManager.USER_TYPE_PROFILE_MANAGED -> UserTypeHint.MANAGED
                    UserManager.USER_TYPE_PROFILE_PRIVATE -> UserTypeHint.PRIVATE
                    else -> UserTypeHint.OTHER
                }
            },
            quiet,
            running,
            unlocked,
            previousKind,
        )
    } catch (_: Throwable) {
        fallbackDescriptor(profileId, owner, userType, quiet, running, unlocked, previousKind)
    }

    internal fun fallbackDescriptor(
        profileId: Long,
        owner: Boolean,
        userType: String?,
        quiet: Boolean,
        running: Boolean,
        unlocked: Boolean,
        previousKind: ProfileKind? = null,
    ): ProfileDescriptor {
        // A failed user-type lookup must not reclassify a known profile: keep the
        // previous kind so transient API failures cannot purge its customization.
        val kind = when {
            owner -> ProfileKind.PERSONAL
            userType == UserManager.USER_TYPE_PROFILE_MANAGED -> ProfileKind.WORK
            userType == UserManager.USER_TYPE_PROFILE_PRIVATE -> ProfileKind.PRIVATE
            userType == null && previousKind != null -> previousKind
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
    private var discovery = ProfileDiscoveryState()

    fun refresh(): ProfileChange {
        val previousKinds = current.mapValues { it.value.descriptor.kind }
        val handles = mutableMapOf<Long, UserHandle>()
        val observations = launcherApps.profiles.mapNotNull { user ->
            val serial = try {
                userManager.getSerialNumberForUser(user)
            } catch (_: RuntimeException) {
                null
            }?.takeIf { it >= 0 }
            // A failed lookup keeps the last known serial for this handle: the
            // reconciler hides and preserves it instead of deriving a removal.
            // A never-seen handle with no serial has nothing to preserve or purge.
            val profileId = serial ?: current.values.firstOrNull { it.user == user }
                ?.descriptor?.profileId?.toLong()
                ?: return@mapNotNull null
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
            val running = try {
                userManager.isUserRunning(user)
            } catch (_: RuntimeException) {
                false
            }
            val unlocked = try {
                userManager.isUserUnlocked(user)
            } catch (_: RuntimeException) {
                false
            }
            handles[profileId] = user
            ProfileObservationInput(profileId, user == owner, userType, quiet, running, unlocked, serial != null)
        }
        val reconciled = ProfileDiscovery.reconcile(discovery, observations, previousKinds)
        val next = reconciled.descriptors.associate { descriptor ->
            val profileId = descriptor.profileId.toLong()
            profileId to AndroidProfile(descriptor, checkNotNull(handles[profileId]))
        }
        val removed = reconciled.removedProfileIds
        val inaccessible = next.filter { (profileId, profile) ->
            profile.descriptor.access != ProfileAccess.AVAILABLE &&
                current[profileId]?.descriptor?.access == ProfileAccess.AVAILABLE
        }.keys
        val available = next.filter { (profileId, profile) ->
            profile.descriptor.access == ProfileAccess.AVAILABLE &&
                current[profileId]?.descriptor?.access?.let { it != ProfileAccess.AVAILABLE } == true
        }.keys
        current = next
        discovery = reconciled.state
        return ProfileChange(
            next.values.sortedBy { it.descriptor.profileId },
            removed,
            inaccessible,
            available,
            discovery.uncertain,
        )
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
