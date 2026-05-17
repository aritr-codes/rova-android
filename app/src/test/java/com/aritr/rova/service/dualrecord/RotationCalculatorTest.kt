package com.aritr.rova.service.dualrecord

import android.view.Surface
import com.aritr.rova.service.dualrecord.internal.RotationCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 6.1a — pinned-rotation strategy. Phase 6.1b smoke-fix #4 made
 * both sides return `displayRotation` passthrough (landscape used to add
 * a quarter-turn): per-side UV rotation in [EglRouter] now produces
 * already-oriented pixels in each encoder file, so the muxer hint
 * (consumed via `DualMuxer.setOrientationHint`) is identical for both
 * sides. See the `RotationCalculator` KDoc for the full reasoning.
 *
 * Returned integer is a Surface.ROTATION_* constant (0/90/180/270 degrees).
 */
class RotationCalculatorTest {

    @Test
    fun `portrait at ROTATION_0 returns 0`() {
        assertEquals(0, RotationCalculator.tag(Surface.ROTATION_0, VideoSide.PORTRAIT))
    }

    @Test
    fun `portrait at ROTATION_90 returns 90`() {
        assertEquals(90, RotationCalculator.tag(Surface.ROTATION_90, VideoSide.PORTRAIT))
    }

    @Test
    fun `portrait at ROTATION_180 returns 180`() {
        assertEquals(180, RotationCalculator.tag(Surface.ROTATION_180, VideoSide.PORTRAIT))
    }

    @Test
    fun `portrait at ROTATION_270 returns 270`() {
        assertEquals(270, RotationCalculator.tag(Surface.ROTATION_270, VideoSide.PORTRAIT))
    }

    @Test
    fun `landscape at ROTATION_0 is passthrough 0 (smoke-fix #4)`() {
        // Was 90 (quarter-turn) under the 6.1a "tag-and-let-player-rotate"
        // model. Path A produces landscape pixels in the landscape file,
        // so the hint matches portrait's hint.
        assertEquals(0, RotationCalculator.tag(Surface.ROTATION_0, VideoSide.LANDSCAPE))
    }

    @Test
    fun `landscape at ROTATION_90 is passthrough 90 (smoke-fix #4)`() {
        assertEquals(90, RotationCalculator.tag(Surface.ROTATION_90, VideoSide.LANDSCAPE))
    }

    @Test
    fun `landscape at ROTATION_180 is passthrough 180 (smoke-fix #4)`() {
        assertEquals(180, RotationCalculator.tag(Surface.ROTATION_180, VideoSide.LANDSCAPE))
    }

    @Test
    fun `landscape at ROTATION_270 is passthrough 270 (smoke-fix #4)`() {
        assertEquals(270, RotationCalculator.tag(Surface.ROTATION_270, VideoSide.LANDSCAPE))
    }

    @Test
    fun `both sides agree at every displayRotation (smoke-fix #4 invariant)`() {
        // Asserts the new invariant: PORTRAIT and LANDSCAPE produce the
        // same hint for any displayRotation. A regression that re-adds an
        // asymmetric `+N°` for landscape would break this single
        // assertion, catching the bug without needing the four per-row
        // checks above.
        for (rot in 0..3) {
            assertEquals(
                "displayRotation=$rot: sides should agree",
                RotationCalculator.tag(rot, VideoSide.PORTRAIT),
                RotationCalculator.tag(rot, VideoSide.LANDSCAPE)
            )
        }
    }
}
