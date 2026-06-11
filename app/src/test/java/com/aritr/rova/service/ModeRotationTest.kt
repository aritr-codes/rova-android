package com.aritr.rova.service

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ADR-0029 PR-γ — Pure-JVM coverage of [computeTargetRotation]: normalises a
 * display rotation to the four valid `Surface.ROTATION_*` constants (identity
 * transform). The legacy mode-offset parameter was removed; orientation policy
 * is now handled by OrientationPolicyResolver at segment boundaries.
 *
 * No Android framework needed at runtime: [Surface.ROTATION_0] etc. are
 * integer constants (0/1/2/3) resolved at compile time from `android.jar`.
 * Same posture as [RovaRecordingServiceDurationTest].
 */
class ModeRotationTest {

    @Test
    fun rotation0_returnsRotation0() {
        assertEquals(Surface.ROTATION_0, computeTargetRotation(Surface.ROTATION_0))
    }

    @Test
    fun rotation90_returnsRotation90() {
        assertEquals(Surface.ROTATION_90, computeTargetRotation(Surface.ROTATION_90))
    }

    @Test
    fun rotation180_returnsRotation180() {
        assertEquals(Surface.ROTATION_180, computeTargetRotation(Surface.ROTATION_180))
    }

    @Test
    fun rotation270_returnsRotation270() {
        assertEquals(Surface.ROTATION_270, computeTargetRotation(Surface.ROTATION_270))
    }

    @Test
    fun unknownRotation_clampsToRotation0() {
        assertEquals(Surface.ROTATION_0, computeTargetRotation(99))
    }
}
