package com.aritr.rova.service.export

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-helper tests for the pre-Q (Tier2/3) move-OUT crash-resume decision.
 * No framework: the Android delete/rename lives in [VaultAndroidOps]; the
 * BRANCH choice is extracted here so it is JVM-testable (house pattern).
 */
class PreQMoveOutResumeTest {

    private val part = "/Movies/Rova/Rova_x.mp4.part"
    private val target = "/Movies/Rova/Rova_x.mp4"

    @Test
    fun noCommittedPath_proceedsFresh() {
        val action = PreQMoveOutResume.decide(
            committedPartPath = null,
            targetExistsAndAdoptable = false,
            partExists = false,
        )
        assertEquals(PreQResumeAction.Proceed, action)
    }

    @Test
    fun targetAdoptable_adoptsExistingTargetNoSecondCopy() {
        val action = PreQMoveOutResume.decide(
            committedPartPath = part,
            targetExistsAndAdoptable = true,
            partExists = false,
        )
        assertEquals(PreQResumeAction.AdoptExistingTarget(target), action)
    }

    @Test
    fun targetAdoptableWithLeftoverPart_stillAdoptsTarget() {
        val action = PreQMoveOutResume.decide(
            committedPartPath = part,
            targetExistsAndAdoptable = true,
            partExists = true,
        )
        assertEquals(PreQResumeAction.AdoptExistingTarget(target), action)
    }

    @Test
    fun targetMissingPartExists_deletesStalePartThenProceeds() {
        val action = PreQMoveOutResume.decide(
            committedPartPath = part,
            targetExistsAndAdoptable = false,
            partExists = true,
        )
        assertEquals(PreQResumeAction.DeleteStalePartThenProceed(part), action)
    }

    @Test
    fun targetNotAdoptableSizeMismatchOrTruncated_notAdoptedDeletesPartThenProceeds() {
        // A truncated/zero-byte OR foreign (wrong-size) file at the target path
        // must NOT be adopted — caller passes adoptable=false (size mismatch).
        val action = PreQMoveOutResume.decide(
            committedPartPath = part,
            targetExistsAndAdoptable = false,
            partExists = true,
        )
        assertEquals(PreQResumeAction.DeleteStalePartThenProceed(part), action)
    }

    @Test
    fun bothMissing_proceedsFresh() {
        val action = PreQMoveOutResume.decide(
            committedPartPath = part,
            targetExistsAndAdoptable = false,
            partExists = false,
        )
        assertEquals(PreQResumeAction.Proceed, action)
    }
}
