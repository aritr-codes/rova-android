package com.aritr.rova.service.dualrecord

import android.view.Surface
import com.aritr.rova.service.dualrecord.internal.RotationCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6.1a → smoke-fix #4 → smoke-fix #5. The current invariant is:
 * `tag(...)` returns 0 for every (displayRotation, side) pair because
 * the [EglRouter]'s per-frame consumer-aspect-aware cropMatrix already
 * produces upright pixels in each encoder file. See the
 * `RotationCalculator` KDoc for the history.
 *
 * Returned integer is a Surface.ROTATION_* compatible degree value (one
 * of 0/90/180/270); smoke-fix #5 collapses it to a constant 0.
 */
class RotationCalculatorTest {

    @Test
    fun `portrait at ROTATION_0 returns 0`() {
        assertEquals(0, RotationCalculator.tag(Surface.ROTATION_0, VideoSide.PORTRAIT))
    }

    @Test
    fun `portrait at ROTATION_90 returns 0 (smoke-fix #5)`() {
        // Was 90 under smoke-fix #4 passthrough; #5's consumer-aspect-
        // aware cropMatrix removes the need for any non-zero hint.
        assertEquals(0, RotationCalculator.tag(Surface.ROTATION_90, VideoSide.PORTRAIT))
    }

    @Test
    fun `portrait at ROTATION_180 returns 0 (smoke-fix #5)`() {
        assertEquals(0, RotationCalculator.tag(Surface.ROTATION_180, VideoSide.PORTRAIT))
    }

    @Test
    fun `portrait at ROTATION_270 returns 0 (smoke-fix #5)`() {
        assertEquals(0, RotationCalculator.tag(Surface.ROTATION_270, VideoSide.PORTRAIT))
    }

    @Test
    fun `landscape at ROTATION_0 returns 0`() {
        assertEquals(0, RotationCalculator.tag(Surface.ROTATION_0, VideoSide.LANDSCAPE))
    }

    @Test
    fun `landscape at ROTATION_90 returns 0 (smoke-fix #5)`() {
        assertEquals(0, RotationCalculator.tag(Surface.ROTATION_90, VideoSide.LANDSCAPE))
    }

    @Test
    fun `landscape at ROTATION_180 returns 0 (smoke-fix #5)`() {
        assertEquals(0, RotationCalculator.tag(Surface.ROTATION_180, VideoSide.LANDSCAPE))
    }

    @Test
    fun `landscape at ROTATION_270 returns 0 (smoke-fix #5)`() {
        assertEquals(0, RotationCalculator.tag(Surface.ROTATION_270, VideoSide.LANDSCAPE))
    }

    @Test
    fun `both sides agree at every displayRotation (smoke-fix #4 invariant)`() {
        for (rot in 0..3) {
            assertEquals(
                "displayRotation=$rot: sides should agree",
                RotationCalculator.tag(rot, VideoSide.PORTRAIT),
                RotationCalculator.tag(rot, VideoSide.LANDSCAPE)
            )
        }
    }

    @Test
    fun `invalid displayRotation throws (regression on input validation)`() {
        // Even with the always-0 contract, the input-range check is
        // still asserted — a -1 or 4 would indicate a caller bug and is
        // worth surfacing.
        runCatching { RotationCalculator.tag(-1, VideoSide.PORTRAIT) }.let {
            assertTrue("expected throw on -1, got ${it.getOrNull()}", it.isFailure)
        }
        runCatching { RotationCalculator.tag(4, VideoSide.LANDSCAPE) }.let {
            assertTrue("expected throw on 4, got ${it.getOrNull()}", it.isFailure)
        }
    }
}
