package com.aritr.rova.service

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 6 — Mode picker.
 * Pure-JVM coverage of [computeTargetRotation]: the display-rotation +
 * mode → CameraX target-rotation arithmetic extracted from
 * [RovaRecordingService].
 *
 * No Android framework needed at runtime: [Surface.ROTATION_0] etc. are
 * integer constants (0/1/2/3) resolved at compile time from `android.jar`.
 * Same posture as [RovaRecordingServiceDurationTest].
 */
class ModeRotationTest {

    @Test
    fun portrait_rotation0_returnsRotation0() {
        assertEquals(Surface.ROTATION_0, computeTargetRotation(Surface.ROTATION_0, "Portrait"))
    }

    @Test
    fun portrait_rotation90_returnsRotation90() {
        assertEquals(Surface.ROTATION_90, computeTargetRotation(Surface.ROTATION_90, "Portrait"))
    }

    @Test
    fun portrait_rotation180_returnsRotation180() {
        assertEquals(Surface.ROTATION_180, computeTargetRotation(Surface.ROTATION_180, "Portrait"))
    }

    @Test
    fun portrait_rotation270_returnsRotation270() {
        assertEquals(Surface.ROTATION_270, computeTargetRotation(Surface.ROTATION_270, "Portrait"))
    }

    @Test
    fun landscape_rotation0_returnsRotation90() {
        assertEquals(Surface.ROTATION_90, computeTargetRotation(Surface.ROTATION_0, "Landscape"))
    }

    @Test
    fun landscape_rotation90_returnsRotation180() {
        assertEquals(Surface.ROTATION_180, computeTargetRotation(Surface.ROTATION_90, "Landscape"))
    }

    @Test
    fun landscape_rotation180_returnsRotation270() {
        assertEquals(Surface.ROTATION_270, computeTargetRotation(Surface.ROTATION_180, "Landscape"))
    }

    @Test
    fun landscape_rotation270_returnsRotation0() {
        assertEquals(Surface.ROTATION_0, computeTargetRotation(Surface.ROTATION_270, "Landscape"))
    }
}
