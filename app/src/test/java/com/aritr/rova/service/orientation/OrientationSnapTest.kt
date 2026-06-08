package com.aritr.rova.service.orientation

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PR-α (ADR-0029 §Decision 2) — pure-JVM tests for [snapOrientation].
 * Same shape as [com.aritr.rova.ui.signals.ThermalHysteresisTest]: a pure
 * (raw, state, now) -> state dwell step with asymmetric hysteresis.
 * Bucket map: [315..360)u[0..45)=ROTATION_0, [45..135)=ROTATION_270,
 * [135..225)=ROTATION_180, [225..315)=ROTATION_90.
 */
class OrientationSnapTest {

    private fun stableAt(rot: Int) = OrientationSnapState(stable = rot, candidate = null, candidateSinceMs = null)

    @Test fun `unknown is ignored — state returned unchanged`() {
        val current = stableAt(Surface.ROTATION_0)
        val result = snapOrientation(degrees = -1, current = current, nowMs = 1000L)
        assertEquals(Surface.ROTATION_0, result.stable)
        assertNull(result.candidate)
        assertNull(result.candidateSinceMs)
    }

    @Test fun `raw squarely in current bucket clears any in-flight candidate`() {
        val current = OrientationSnapState(
            stable = Surface.ROTATION_0,
            candidate = Surface.ROTATION_270,
            candidateSinceMs = 500L,
        )
        // 10 degrees is squarely inside the ROTATION_0 bucket.
        val result = snapOrientation(degrees = 10, current = current, nowMs = 1000L)
        assertEquals(Surface.ROTATION_0, result.stable)
        assertNull("candidate cleared once raw returns to the stable bucket", result.candidate)
        assertNull(result.candidateSinceMs)
    }

    @Test fun `new bucket under dwell holds stable, sets candidate`() {
        val current = stableAt(Surface.ROTATION_0)
        // 90 is squarely inside ROTATION_270 bucket (45..135).
        val result = snapOrientation(degrees = 90, current = current, nowMs = 2000L, dwellMs = 350L)
        assertEquals("stable unchanged before dwell elapses", Surface.ROTATION_0, result.stable)
        assertEquals(Surface.ROTATION_270, result.candidate)
        assertEquals(2000L, result.candidateSinceMs)
    }

    @Test fun `new bucket after dwell flips stable, clears candidate`() {
        val current = OrientationSnapState(
            stable = Surface.ROTATION_0,
            candidate = Surface.ROTATION_270,
            candidateSinceMs = 1000L,
        )
        val result = snapOrientation(degrees = 90, current = current, nowMs = 1400L, dwellMs = 350L)
        assertEquals("dwell elapsed (400 >= 350) -> flip", Surface.ROTATION_270, result.stable)
        assertNull(result.candidate)
        assertNull(result.candidateSinceMs)
    }

    @Test fun `multi-event during dwell does NOT restart the timer`() {
        var s = stableAt(Surface.ROTATION_0)
        s = snapOrientation(degrees = 90, current = s, nowMs = 1000L, dwellMs = 350L)
        assertEquals(1000L, s.candidateSinceMs)
        // second event for the SAME candidate at t=1200 must keep since=1000.
        s = snapOrientation(degrees = 100, current = s, nowMs = 1200L, dwellMs = 350L)
        assertEquals("timer not restarted on further same-bucket events", 1000L, s.candidateSinceMs)
        assertEquals(Surface.ROTATION_0, s.stable)
        // third event at t=1400: 400 >= 350 from the ORIGINAL start -> flip.
        s = snapOrientation(degrees = 100, current = s, nowMs = 1400L, dwellMs = 350L)
        assertEquals(Surface.ROTATION_270, s.stable)
    }

    @Test fun `oscillation inside dead-band never flips`() {
        var s = stableAt(Surface.ROTATION_0)
        // 44 and 46 straddle the 45 boundary, both within 12 deg dead-band.
        s = snapOrientation(degrees = 44, current = s, nowMs = 1000L)
        s = snapOrientation(degrees = 46, current = s, nowMs = 1400L)
        s = snapOrientation(degrees = 44, current = s, nowMs = 1800L)
        assertEquals("dead-band absorbs straddle", Surface.ROTATION_0, s.stable)
        assertNull("no candidate started inside dead-band", s.candidate)
    }

    @Test fun `exact boundary degree falls into higher bucket (deterministic)`() {
        // 60 is clear of dead-band and inside [45..135) -> ROTATION_270.
        assertEquals(Surface.ROTATION_270, bucketOf(60))
        // 200 -> ROTATION_180; 280 -> ROTATION_90; 350 and 10 -> ROTATION_0.
        assertEquals(Surface.ROTATION_180, bucketOf(200))
        assertEquals(Surface.ROTATION_90, bucketOf(280))
        assertEquals(Surface.ROTATION_0, bucketOf(350))
        assertEquals(Surface.ROTATION_0, bucketOf(10))
    }

    @Test fun `negative non-sentinel degrees are normalized, not treated as unknown`() {
        // Only -1 is the UNKNOWN sentinel; other negatives normalize.
        val current = stableAt(Surface.ROTATION_0)
        val result = snapOrientation(degrees = -90, current = current, nowMs = 1000L)
        // -90 normalizes to 270, which is on the 270 dead-band edge -> held.
        assertEquals(Surface.ROTATION_0, result.stable)
    }

    // --- first-sample fallback (deterministic order + source tag) ---

    @Test fun `firstSampleFallback prefers lastEffective when present`() {
        val r = firstSampleFallback(lastEffective = Surface.ROTATION_90, snappedDisplayRotation = Surface.ROTATION_180)
        assertEquals(Surface.ROTATION_90, r.rotation)
        assertEquals(FirstSampleSource.LAST_EFFECTIVE, r.source)
    }

    @Test fun `firstSampleFallback falls to display when lastEffective null`() {
        val r = firstSampleFallback(lastEffective = null, snappedDisplayRotation = Surface.ROTATION_180)
        assertEquals(Surface.ROTATION_180, r.rotation)
        assertEquals(FirstSampleSource.DISPLAY_ROTATION, r.source)
    }

    @Test fun `firstSampleFallback defaults to portrait when both null`() {
        val r = firstSampleFallback(lastEffective = null, snappedDisplayRotation = null)
        assertEquals(Surface.ROTATION_0, r.rotation)
        assertEquals(FirstSampleSource.DEFAULT_PORTRAIT, r.source)
    }
}
