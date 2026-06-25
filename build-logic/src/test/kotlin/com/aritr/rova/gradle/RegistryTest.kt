package com.aritr.rova.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RegistryTest {

    /** Every registered id resolves; unknown ids throw (never silently pass). */
    @Test
    fun unknownIdThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            RovaGateRules.run("checkDoesNotExist", emptyList())
        }
    }

    /**
     * The registry must hold EXACTLY the 46 gate ids — no missing/extra/typo.
     * Update EXPECTED_IDS as each batch lands; final value is all 46.
     */
    @Test
    fun registryHoldsExactlyTheExpectedIds() {
        assertEquals(EXPECTED_IDS, RovaGateRules.registry.keys.toSortedSet())
    }

    companion object {
        // Grows per batch; Task N (final wiring) asserts all 46.
        val EXPECTED_IDS = sortedSetOf(
            "checkAtomicTerminalWriteForbiddenPair",
            "checkAudioModeFgsTypeMatch",
            "checkExportCleanupPredicate",
            "checkExportNoCopyToPublicMovies",
            "checkExportTierReadTolerant",
            "checkExternalRootShared",
            "checkFGSStartGuarded",
            "checkPendingFdModeIsRW",
            "checkRecoveryNoDeletion",
            "checkRecoveryReceiverCounter",
            "checkRecoverySegmentRegex",
            "checkScanFileBoundedWait",
            "checkScanTriggerSingleSite",
            "checkScheduleReceiverNoFgsStart",
            "checkSchedulerNoGetService",
            "checkStopNoGetService",
            "checkUserStoppedBeforeMerge",
        )
    }
}
