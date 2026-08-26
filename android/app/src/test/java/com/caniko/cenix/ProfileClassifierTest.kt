package com.caniko.cenix

import android.os.UserManager
import com.caniko.cenix.uniffi.ProfileAccess
import com.caniko.cenix.uniffi.ProfileKind
import org.junit.Assert.assertEquals
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
}
