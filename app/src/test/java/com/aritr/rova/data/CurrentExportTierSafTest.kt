package com.aritr.rova.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentExportTierSafTest {
    @Test fun safFolder_forces_SAF_tier_regardless_of_sdk() {
        assertEquals(ExportTier.SAF_DESTINATION, currentExportTier(hasUsableSafFolder = true))
    }
    @Test fun noSafFolder_uses_sdk_tier() {
        // On the JVM test runtime SDK_INT is 0 → TIER3 branch.
        assertEquals(currentExportTier(), currentExportTier(hasUsableSafFolder = false))
    }
}
