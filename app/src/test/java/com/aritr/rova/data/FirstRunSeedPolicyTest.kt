package com.aritr.rova.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunSeedPolicyTest {

    @Test fun freshInstallSeeds() {
        // Never seeded AND no recording pref written yet -> genuinely fresh.
        assertTrue(FirstRunSeedPolicy.shouldSeed(presetSeeded = false, anyRecordingPrefPresent = false))
    }

    @Test fun existingUserNeverClobbered() {
        // A recording pref already exists -> existing user, do NOT seed.
        assertFalse(FirstRunSeedPolicy.shouldSeed(presetSeeded = false, anyRecordingPrefPresent = true))
    }

    @Test fun alreadySeededDoesNotReseed() {
        assertFalse(FirstRunSeedPolicy.shouldSeed(presetSeeded = true, anyRecordingPrefPresent = false))
    }
}
