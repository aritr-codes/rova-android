package com.aritr.rova.ui.screens.chrome

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class ChromeSpinTest {

    // Counter-rotation table (spec §2.3). Signs are the empirical convention
    // verified at Task 10 Step 3; if the device shows inverted spin, flip ONLY
    // uiCounterRotationDegrees (single site).
    @Test fun counterRotation_table() {
        assertEquals(0f, uiCounterRotationDegrees(Surface.ROTATION_0), 0f)
        assertEquals(90f, uiCounterRotationDegrees(Surface.ROTATION_90), 0f)
        assertEquals(180f, uiCounterRotationDegrees(Surface.ROTATION_180), 0f)
        assertEquals(-90f, uiCounterRotationDegrees(Surface.ROTATION_270), 0f)
    }

    @Test fun counterRotation_garbageDefaultsToZero() {
        assertEquals(0f, uiCounterRotationDegrees(-1), 0f)
        assertEquals(0f, uiCounterRotationDegrees(7), 0f)
    }

    // Shortest-path delta (research §3): result always in [-180, 180).
    @Test fun shortestPath_simple() {
        assertEquals(90f, shortestPathDelta(0f, 90f), 0f)
        assertEquals(-90f, shortestPathDelta(90f, 0f), 0f)
    }

    @Test fun shortestPath_crossesSeam() {
        // 270 -> 0 must be +90, NOT -270 (no long-way spins).
        assertEquals(90f, shortestPathDelta(270f, 0f), 0f)
        assertEquals(-90f, shortestPathDelta(0f, 270f), 0f)
    }

    @Test fun shortestPath_unwrappedAccumulator() {
        // current may be far outside [0,360) after many spins.
        assertEquals(90f, shortestPathDelta(720f, 90f), 0f)
        assertEquals(-90f, shortestPathDelta(-630f, 0f), 0f)
    }

    @Test fun shortestPath_halfTurnIsPlus180() {
        // ±180 ambiguity resolved to +180 consistently.
        assertEquals(180f, shortestPathDelta(0f, 180f), 0f)
    }

    // Fade for over-slot labels (spec §5): opaque upright, gone by 45°.
    @Test fun uprightFade_uprightIsOpaque() {
        assertEquals(1f, uprightFadeAlpha(0f), 0f)
        assertEquals(1f, uprightFadeAlpha(360f), 0f)
        assertEquals(1f, uprightFadeAlpha(-720f), 0f)
    }

    @Test fun uprightFade_goneAtRightAnglesBothSenses() {
        assertEquals(0f, uprightFadeAlpha(90f), 0f)
        assertEquals(0f, uprightFadeAlpha(-90f), 0f)
        assertEquals(0f, uprightFadeAlpha(180f), 0f)
        assertEquals(0f, uprightFadeAlpha(-630f), 0f) // unwrapped accumulator, ≡ 90
    }

    @Test fun uprightFade_linearMidSpin() {
        assertEquals(0.5f, uprightFadeAlpha(22.5f), 1e-6f)
        assertEquals(0.5f, uprightFadeAlpha(-22.5f), 1e-6f)
    }

    @Test fun accumulator_neverDriftsFromTarget() {
        // applying delta lands exactly on target mod 360
        var angle = 0f
        intArrayOf(Surface.ROTATION_90, Surface.ROTATION_270, Surface.ROTATION_180, Surface.ROTATION_0).forEach { r ->
            angle += shortestPathDelta(angle, uiCounterRotationDegrees(r))
            val mod = (((angle - uiCounterRotationDegrees(r)) % 360f) + 360f) % 360f
            assertEquals(0f, mod, 0f)
        }
    }
}
