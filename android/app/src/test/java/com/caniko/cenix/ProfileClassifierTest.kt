package com.caniko.cenix

import android.os.UserManager
import com.caniko.cenix.uniffi.ProfileAccess
import com.caniko.cenix.uniffi.ProfileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileClassifierTest {
    @Test
    fun classifiesStablePublicProfileTypesAndAccess() {
        assertEquals(
            ProfileKind.PERSONAL to ProfileAccess.AVAILABLE,
            ProfileClassifier.descriptor(0, true, null, false, true, true).let { it.kind to it.access },
        )
        assertEquals(
            ProfileKind.WORK to ProfileAccess.QUIET,
            ProfileClassifier.descriptor(
                10,
                false,
                UserManager.USER_TYPE_PROFILE_MANAGED,
                true,
                true,
                false,
            ).let { it.kind to it.access },
        )
        assertEquals(
            ProfileKind.PRIVATE to ProfileAccess.LOCKED,
            ProfileClassifier.descriptor(
                11,
                false,
                UserManager.USER_TYPE_PROFILE_PRIVATE,
                true,
                true,
                false,
            ).let { it.kind to it.access },
        )
        assertEquals(
            ProfileKind.OTHER to ProfileAccess.UNAVAILABLE,
            ProfileClassifier.descriptor(12, false, "other", false, false, false).let { it.kind to it.access },
        )
    }

    @Test
    fun unknownUserTypeKeepsPreviousKindInsteadOfPurging() {
        assertEquals(
            ProfileKind.WORK to ProfileAccess.UNAVAILABLE,
            ProfileClassifier.descriptor(10, false, null, false, false, false, ProfileKind.WORK)
                .let { it.kind to it.access },
        )
        assertEquals(
            ProfileKind.PRIVATE to ProfileAccess.UNAVAILABLE,
            ProfileClassifier.descriptor(11, false, null, false, false, false, ProfileKind.PRIVATE)
                .let { it.kind to it.access },
        )
        // No history: unknown stays OTHER.
        assertEquals(
            ProfileKind.OTHER to ProfileAccess.UNAVAILABLE,
            ProfileClassifier.descriptor(12, false, null, false, false, false).let { it.kind to it.access },
        )
    }

    @Test
    fun repeatedDiscoveryFailuresStayUncertainUntilAuthoritative() {
        var state = ProfileDiscoveryState()
        state = ProfileDiscovery.update(
            state,
            listOf(ProfileDiscoveryInput(77L, false, null)),
            setOf(77L),
        )
        assertEquals(setOf(77L), state.uncertain)
        assertTrue(state.authoritativeKinds.isEmpty())

        state = ProfileDiscovery.update(
            state,
            listOf(ProfileDiscoveryInput(77L, false, null)),
            setOf(77L),
        )
        assertEquals(setOf(77L), state.uncertain)
        assertTrue(state.authoritativeKinds.isEmpty())

        state = ProfileDiscovery.update(
            state,
            listOf(ProfileDiscoveryInput(77L, false, UserManager.USER_TYPE_PROFILE_MANAGED)),
            setOf(77L),
        )
        assertEquals(setOf(77L), state.authoritativeKinds)
        assertTrue(state.uncertain.isEmpty())
    }

    @Test
    fun ownerSuccessAndRemovedProfilesUpdateDiscoveryState() {
        var state = ProfileDiscovery.update(
            ProfileDiscoveryState(),
            listOf(ProfileDiscoveryInput(0L, true, null)),
            setOf(0L),
        )
        assertEquals(setOf(0L), state.authoritativeKinds)
        assertTrue(state.uncertain.isEmpty())

        state = ProfileDiscovery.update(state, emptyList(), emptySet())
        assertTrue(state.authoritativeKinds.isEmpty())
        assertTrue(state.uncertain.isEmpty())
    }

    @Test
    fun previousKindRequiresAuthoritativeState() {
        val kinds = mapOf(77L to ProfileKind.WORK)
        assertEquals(
            ProfileKind.WORK,
            ProfileDiscovery.previousKind(ProfileDiscoveryState(setOf(77L), emptySet()), kinds, 77L),
        )
        assertEquals(
            null,
            ProfileDiscovery.previousKind(ProfileDiscoveryState(emptySet(), setOf(77L)), kinds, 77L),
        )
    }
}
