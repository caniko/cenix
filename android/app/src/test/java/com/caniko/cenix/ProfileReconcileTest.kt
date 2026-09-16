package com.caniko.cenix

import android.os.UserManager
import com.caniko.cenix.uniffi.ProfileAccess
import com.caniko.cenix.uniffi.ProfileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileReconcileTest {
    @Test
    fun failedSerialHidesAndPreservesInsteadOfRemoving() {
        val reconciled = ProfileDiscovery.reconcile(
            ProfileDiscoveryState(setOf(10L), emptySet()),
            listOf(ProfileObservationInput(10L, false, null, false, false, false, false)),
            mapOf(10L to ProfileKind.WORK),
        )
        val descriptor = reconciled.descriptors.single()
        assertEquals(ProfileKind.WORK, descriptor.kind)
        assertEquals(ProfileAccess.UNAVAILABLE, descriptor.access)
        assertTrue(reconciled.removedProfileIds.isEmpty())
        // Previously authoritative stays authoritative; rows are preserved.
        assertEquals(setOf(10L), reconciled.state.authoritativeKinds)
    }

    @Test
    fun onlyGenuinelyAbsentSerialsAreRemoved() {
        val reconciled = ProfileDiscovery.reconcile(
            ProfileDiscoveryState(),
            listOf(
                ProfileObservationInput(10L, false, UserManager.USER_TYPE_PROFILE_MANAGED, false, true, true, true),
            ),
            mapOf(10L to ProfileKind.WORK, 11L to ProfileKind.PRIVATE),
        )
        assertEquals(setOf(11L), reconciled.removedProfileIds)
        assertEquals(ProfileAccess.AVAILABLE, reconciled.descriptors.single().access)
    }

    @Test
    fun fallbackMirrorsNativeReconciliation() {
        val observations = listOf(
            ProfileObservationInput(10L, false, UserManager.USER_TYPE_PROFILE_MANAGED, false, true, true, true),
            ProfileObservationInput(11L, false, null, false, false, false, false),
        )
        val previous = mapOf(10L to ProfileKind.WORK, 11L to ProfileKind.PRIVATE)
        val state = ProfileDiscoveryState(setOf(10L, 11L), emptySet())
        val native = ProfileDiscovery.reconcile(state, observations, previous)
        val fallback = ProfileDiscovery.fallbackReconcile(state, observations, previous)
        assertEquals(
            native.descriptors.map { it.profileId to (it.kind to it.access) },
            fallback.descriptors.map { it.profileId to (it.kind to it.access) },
        )
        assertEquals(native.state, fallback.state)
        assertEquals(native.removedProfileIds, fallback.removedProfileIds)
    }
}
