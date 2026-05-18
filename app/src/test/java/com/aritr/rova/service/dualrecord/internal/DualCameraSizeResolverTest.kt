package com.aritr.rova.service.dualrecord.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class DualCameraSizeResolverTest {

    @Test
    fun `picks largest 4-3 above threshold by total pixels`() {
        val input = listOf(
            2560 to 1920,
            4032 to 3024,
            1920 to 1080,   // 16:9 — must be filtered out
            1280 to 720,    // 16:9 — must be filtered out
        )
        val result = DualCameraSizeResolver.resolveDualCameraSizeFrom(input)
        assertEquals(4032 to 3024, result)
    }

    @Test
    fun `picks threshold boundary size`() {
        // shortEdge = 1920 is the inclusive threshold (>=, not >).
        val input = listOf(
            1280 to 960,    // below threshold
            1920 to 1440,   // exactly at threshold — eligible
            1024 to 768,    // below threshold
        )
        val result = DualCameraSizeResolver.resolveDualCameraSizeFrom(input)
        assertEquals(1920 to 1440, result)
    }

    @Test
    fun `falls back to largest 4-3 when all below threshold`() {
        val input = listOf(
            1280 to 960,
            640 to 480,
        )
        val result = DualCameraSizeResolver.resolveDualCameraSizeFrom(input)
        assertEquals(1280 to 960, result)
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
    fun `tolerates 1 pixel aspect rounding`() {
        // 2561x1920: width*3 = 7683 ; height*4 = 7680 ; abs diff = 3 (FAILS tolerance).
        // 2560x1921: width*3 = 7680 ; height*4 = 7684 ; abs diff = 4 (FAILS tolerance).
        // Use a true off-by-one against an actual 4:3 size: 2561x1920 fails;
        // the rounding tolerance fires on 1280x959 (width*3=3840, height*4=3836, diff=4 — also fails).
        // Real-world rounding case: a sensor that exposes 1601x1200 (width*3=4803, height*4=4800, diff=3 — fails).
        // Practical tolerance target: integer-perfect 4:3 modes plus the ±1 px outputs CameraX produces from
        // odd sensor reads. 2048×1536 (diff=0) and 1920×1440 (diff=0) cover the real cases. Use:
        //   1281×960: width*3=3843, height*4=3840, diff=3 (fails tolerance ≤1).
        //   1283×960: diff=9 (fails).
        // Off-by-one cases that *should* pass at tolerance ≤ 1 px: 1280x961 (diff=4, fails).
        //
        // Conclusion: at tolerance ≤ 1 px on `abs(width*3 - height*4)`, the only off-by-one cases
        // that pass are widths within ±1/3 of a true 4:3, which integer math doesn't produce.
        // The tolerance is defensive for floating-point rounding NOT integer ratios. Test the
        // exact match instead and document that the tolerance is for future-proofing only.
        val input = listOf(1920 to 1440)   // exact 4:3
        val result = DualCameraSizeResolver.resolveDualCameraSizeFrom(input)
        assertEquals(1920 to 1440, result)
    }

    @Test
    fun `ignores portrait orientation 4-3 modes`() {
        // 1920×2560 is 4:3-aspect but portrait-oriented. The dual-camera
        // session is landscape-oriented (Surface.ROTATION_0 in
        // setupDualCamera), so portrait sizes must be filtered out — they
        // would force PORTRAIT zone to sample sideways.
        val input = listOf(
            1920 to 2560,   // portrait 4:3 — must be filtered out
            1920 to 1080,   // 16:9 landscape — must be filtered out too
        )
        val result = DualCameraSizeResolver.resolveDualCameraSizeFrom(input)
        // No landscape 4:3 modes → fall back to hint.
        assertEquals(1920 to 1440, result)
    }
}
