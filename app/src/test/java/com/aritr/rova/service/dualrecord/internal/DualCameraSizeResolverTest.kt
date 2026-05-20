package com.aritr.rova.service.dualrecord.internal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the source-size contract documented on
 * [DualCameraSizeResolver]: largest landscape 4:3 with shortEdge ≥ 1920,
 * else `Size(1920, 1440)` hint. No soft fallback to below-threshold 4:3,
 * no aspect-rounding tolerance, portrait-oriented 4:3 rejected.
 *
 * Threshold note: for a landscape 4:3 mode, the short edge is the
 * `height` in `(width, height)`. The smallest eligible mode is
 * `2560×1920` (height = 1920). The hint value `(1920, 1440)` is
 * intentionally BELOW the eligibility threshold — it's a request to
 * CameraX for the closest available mode, not a claimed pixel-perfect
 * source.
 */
class DualCameraSizeResolverTest {

    @Test
    fun `2560x1920 is the smallest eligible landscape 4-3`() {
        // height = 1920 exactly clears the inclusive threshold (>=, not >).
        val input = listOf(2560 to 1920)
        val result = DualCameraSizeResolver.resolveDualCameraSizeFrom(input)
        assertEquals(2560 to 1920, result)
    }

    @Test
    fun `picks largest 4-3 above threshold by total pixels`() {
        val input = listOf(
            2560 to 1920,    // eligible
            4032 to 3024,    // eligible, larger
            1920 to 1080,    // 16:9 — rejected
            1280 to 720,     // 16:9 — rejected
        )
        val result = DualCameraSizeResolver.resolveDualCameraSizeFrom(input)
        assertEquals(4032 to 3024, result)
    }

    @Test
    fun `1920x1440 in input returns 1920x1440 via hint fallback`() {
        // 1920×1440 has shortEdge = 1440, BELOW the height ≥ 1920
        // threshold — so it's NOT eligible. Eligible set is empty;
        // resolver returns hint (1920, 1440), which happens to equal
        // the input. Pinned here so a future change to the hint value
        // surfaces immediately.
        val input = listOf(1920 to 1440)
        val result = DualCameraSizeResolver.resolveDualCameraSizeFrom(input)
        assertEquals(1920 to 1440, result)
    }

    @Test
    fun `falls back to hint when all 4-3 modes below threshold`() {
        // Contract tightening: prior behavior returned the largest
        // below-threshold 4:3 (e.g. 1280×960 here). Current behavior
        // defers to CameraX via the hint — the resolver never returns
        // a sub-threshold size.
        val input = listOf(
            1280 to 960,    // shortEdge = 960 — below
            640 to 480,     // shortEdge = 480 — below
        )
        val result = DualCameraSizeResolver.resolveDualCameraSizeFrom(input)
        assertEquals(1920 to 1440, result)
    }

    @Test
    fun `falls back to hint when no 4-3 modes`() {
        val input = listOf(
            1920 to 1080,
            1280 to 720,
        )
        val result = DualCameraSizeResolver.resolveDualCameraSizeFrom(input)
        assertEquals(1920 to 1440, result)
    }

    @Test
    fun `ignores portrait-oriented 4-3 modes`() {
        // 1920×2560 is a 4:3-aspect mode but portrait-oriented. The
        // dual-camera session is fixed-landscape (Surface.ROTATION_0),
        // so portrait sizes are filtered out — using one would force the
        // PORTRAIT zone to sample sideways.
        val input = listOf(
            1920 to 2560,   // portrait 4:3 — rejected
            1920 to 1080,   // 16:9 landscape — rejected
        )
        val result = DualCameraSizeResolver.resolveDualCameraSizeFrom(input)
        assertEquals(1920 to 1440, result)
    }
}
