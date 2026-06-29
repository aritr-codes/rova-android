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
     * The registry must hold EXACTLY the 47 gate ids — no missing/extra/typo.
     * Update EXPECTED_IDS as each batch lands; final value is all 47.
     */
    @Test
    fun registryHoldsExactlyTheExpectedIds() {
        assertEquals(EXPECTED_IDS, RovaGateRules.registry.keys.toSortedSet())
    }

    companion object {
        // All 47 gate ids — final registry.
        val EXPECTED_IDS = sortedSetOf(
            "checkA11yAnimationGated",
            "checkA11yClickableHasRole",
            "checkA11yTargetSizeToken",
            "checkAeFpsRangeCapabilityGated",
            "checkAtomicTerminalWriteForbiddenPair",
            "checkAudioModeFgsTypeMatch",
            "checkCompletedWriteOnlyFromPerformMerge",
            "checkExportCleanupPredicate",
            "checkExportIsPendingGuarded",
            "checkExportNoCopyToPublicMovies",
            "checkExportPendingVisibilityOnQuery",
            "checkExportPipelineSingleEntry",
            "checkExportQueryArgMatchPendingGuarded",
            "checkExportSetIncludePendingGuarded",
            "checkExportTierReadTolerant",
            "checkExternalRootShared",
            "checkFGSStartGuarded",
            "checkFrontBackCapabilityGated",
            "checkGlassSurfaceRoleUsage",
            "checkLibraryNoManifestWrite",
            "checkLocaleConfigNoPseudolocale",
            "checkNoHardcodedUiStrings",
            "checkNoLegacyModeStrings",
            "checkPendingFdModeIsRW",
            "checkPresetNoOrientation",
            "checkRecordChromeLockSingleSite",
            "checkRecordSurfaceNoBlur",
            "checkRecoveryNoDeletion",
            "checkRecoveryReceiverCounter",
            "checkRecoverySegmentRegex",
            "checkRovaGlyphHome",
            "checkSafTargetCommittedBeforeStream",
            "checkScanFileBoundedWait",
            "checkScanTriggerSingleSite",
            "checkScheduleReceiverNoFgsStart",
            "checkSchedulerNoGetService",
            "checkSemanticIconNoRawAlpha",
            "checkSetTargetRotationBoundaryOnly",
            "checkSingleColorSchemeSource",
            "checkStatusColorLocked",
            "checkStopNoGetService",
            "checkUserCopyVocabulary",
            "checkUserStoppedBeforeMerge",
            "checkVaultExporterNoPublicPublish",
            "checkWakeLockBoundedAcquire",
            "checkWakeLockHeldRefresh",
            "checkWakeLockZeroGapRefresh",
        )
    }
}
