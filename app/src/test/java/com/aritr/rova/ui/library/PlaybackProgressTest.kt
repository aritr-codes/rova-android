package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v3.3 hairline predicate — the spec's `pgFrac` (ResumePolicy verbatim: position > 0, duration
 * known, not inside the near-end window; fraction honest, capped at 1 defensively).
 */
class PlaybackProgressTest {

    @Test fun `no position or non-positive position renders bare`() {
        assertNull(PlaybackProgress.fraction(null, 60_000L))
        assertNull(PlaybackProgress.fraction(0L, 60_000L))
        assertNull(PlaybackProgress.fraction(-5L, 60_000L))
    }

    @Test fun `unknown duration renders bare (legacy rows)`() {
        assertNull(PlaybackProgress.fraction(30_000L, 0L))
        assertNull(PlaybackProgress.fraction(30_000L, -1L))
    }

    @Test fun `fraction is honest position over duration`() {
        assertEquals(0.5f, PlaybackProgress.fraction(30_000L, 60_000L)!!, 1e-6f)
        // 0.2% of a 2h recording — sub-perceptual on any tile, still truthful, still non-null.
        assertEquals(0.002f, PlaybackProgress.fraction(14_400L, 7_200_000L)!!, 1e-6f)
    }

    @Test fun `near-end window hides the bar (ResumePolicy boundary)`() {
        // 10min: threshold = min(3000, max(1000, 12000)) = 3000ms.
        assertNull(PlaybackProgress.fraction(597_000L, 600_000L))          // exactly at threshold
        assertNotNull(PlaybackProgress.fraction(596_999L, 600_000L))       // 1ms inside
        // 30s: 2% = 600ms -> clamped up to 1000ms.
        assertNull(PlaybackProgress.fraction(29_000L, 30_000L))
        assertNotNull(PlaybackProgress.fraction(28_999L, 30_000L))
    }

    @Test fun `position at or past the end renders bare`() {
        assertNull(PlaybackProgress.fraction(60_000L, 60_000L))
        assertNull(PlaybackProgress.fraction(61_000L, 60_000L))
    }

    @Test fun `percent is the rounded spoken fraction`() {
        assertEquals(37, PlaybackProgress.percent(0.374f))
        assertEquals(38, PlaybackProgress.percent(0.375f))
        assertEquals(0, PlaybackProgress.percent(0.002f))
        assertEquals(100, PlaybackProgress.percent(1f))
    }
}
