package com.aritr.rova.service.dualrecord

import android.view.Surface
import com.aritr.rova.service.dualrecord.internal.RotationCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 6.1a — pinned-rotation strategy: GL shader applies crop+scale only,
 * orientation is signalled via MediaFormat.KEY_ROTATION. Portrait side
 * mirrors `computeTargetRotation(displayRotation, "Portrait")`, landscape
 * mirrors `computeTargetRotation(displayRotation, "Landscape")`.
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
    fun `landscape at ROTATION_0 quarter-turns to 90`() {
        assertEquals(90, RotationCalculator.tag(Surface.ROTATION_0, VideoSide.LANDSCAPE))
    }

    @Test
    fun `landscape at ROTATION_90 quarter-turns to 180`() {
        assertEquals(180, RotationCalculator.tag(Surface.ROTATION_90, VideoSide.LANDSCAPE))
    }

    @Test
    fun `landscape at ROTATION_180 quarter-turns to 270`() {
        assertEquals(270, RotationCalculator.tag(Surface.ROTATION_180, VideoSide.LANDSCAPE))
    }

    @Test
    fun `landscape at ROTATION_270 quarter-turns wraps to 0`() {
        assertEquals(0, RotationCalculator.tag(Surface.ROTATION_270, VideoSide.LANDSCAPE))
    }
}
