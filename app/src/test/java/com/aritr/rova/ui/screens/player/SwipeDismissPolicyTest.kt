package com.aritr.rova.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * player-gestures.html §06 — pins the swipe-down-to-dismiss commit decision +
 * rubber-band scale. JVM-only; no Compose. Mirrors the pure-seam test style of
 * [EdgeSeekZonesTest] / [AutoHideChromePolicyTest].
 */
class SwipeDismissPolicyTest {

    // ── shouldCommit: distance OR downward-fling velocity ────────────────────

    @Test fun commit_whenDistanceReachesThreshold() {
        // ≥ 120dp travel commits regardless of a slow release.
        assertTrue(SwipeDismissPolicy.shouldCommit(dragDy = 120f, velocityDy = 0f))
        assertTrue(SwipeDismissPolicy.shouldCommit(dragDy = 240f, velocityDy = 0f))
    }

    @Test fun commit_whenDownwardFlingReachesThreshold() {
        // A deliberate downward fling commits even on a short travel.
        assertTrue(SwipeDismissPolicy.shouldCommit(dragDy = 30f, velocityDy = 800f))
        assertTrue(SwipeDismissPolicy.shouldCommit(dragDy = 30f, velocityDy = 1500f))
    }

    @Test fun springBack_whenShortAndSlow() {
        // A mid-review nudge: below both thresholds → springs back, no dismiss.
        assertFalse(SwipeDismissPolicy.shouldCommit(dragDy = 119f, velocityDy = 799f))
        assertFalse(SwipeDismissPolicy.shouldCommit(dragDy = 0f, velocityDy = 0f))
    }

    @Test fun upwardGestureNeverCommits() {
        // Upward drag/fling reports negative velocity and (clamped) zero travel —
        // never clears the positive-downward thresholds.
        assertFalse(SwipeDismissPolicy.shouldCommit(dragDy = 0f, velocityDy = -2000f))
        assertFalse(SwipeDismissPolicy.shouldCommit(dragDy = 10f, velocityDy = -900f))
    }

    // ── dismissScale: 1.0 at rest → floor at commit distance ─────────────────

    @Test fun scaleIsUnityAtRest() {
        assertEquals(1f, SwipeDismissPolicy.dismissScale(0f), 0.0001f)
        assertEquals(1f, SwipeDismissPolicy.dismissScale(-50f), 0.0001f)
    }

    @Test fun scaleReachesFloorAtCommitDistance() {
        assertEquals(
            SwipeDismissPolicy.SCALE_FLOOR,
            SwipeDismissPolicy.dismissScale(SwipeDismissPolicy.COMMIT_DISTANCE_DP),
            0.0001f,
        )
    }

    @Test fun scaleIsMonotonicAndClampedPastCommit() {
        val half = SwipeDismissPolicy.dismissScale(SwipeDismissPolicy.COMMIT_DISTANCE_DP / 2f)
        assertTrue(half < 1f && half > SwipeDismissPolicy.SCALE_FLOOR)
        // Over-drag never shrinks below the floor.
        assertEquals(
            SwipeDismissPolicy.SCALE_FLOOR,
            SwipeDismissPolicy.dismissScale(SwipeDismissPolicy.COMMIT_DISTANCE_DP * 4f),
            0.0001f,
        )
    }
}
