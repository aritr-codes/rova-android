package com.aritr.rova.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class WallClockTimelineTest {
    // clip0: starts 09:00:00 (t0), 30s; clip1: starts 09:15:00 (t0+900s), 30s
    private val t0 = 1_700_000_000_000L
    private val starts = listOf(t0, t0 + 900_000L)
    private val durs = listOf(30_000L, 30_000L)
    private val noApprox = listOf(false, false)

    @Test fun `instant inside clip0`() {
        val r = WallClockTimeline.readoutAt(starts, durs, noApprox, positionMs = 10_000L)
        assertEquals(t0 + 10_000L, r.instantMs)
        assertFalse(r.isApprox)
        assertNull(r.gapBeforeMs)
    }
    @Test fun `boundary selects next clip at offset 0 with gap`() {
        val r = WallClockTimeline.readoutAt(starts, durs, noApprox, positionMs = 30_000L)
        assertEquals(t0 + 900_000L, r.instantMs)          // clip1 start
        assertEquals(870_000L, r.gapBeforeMs)              // 900s - 30s
    }
    @Test fun `instant inside clip1`() {
        val r = WallClockTimeline.readoutAt(starts, durs, noApprox, positionMs = 45_000L)
        assertEquals(t0 + 900_000L + 15_000L, r.instantMs)
    }
    @Test fun `position at or beyond total selects last clip end`() {
        val r = WallClockTimeline.readoutAt(starts, durs, noApprox, positionMs = 999_000L)
        assertEquals(t0 + 900_000L + 30_000L, r.instantMs)
    }
    @Test fun `approx propagates from selected clip mask`() {
        val r = WallClockTimeline.readoutAt(starts, durs, listOf(false, true), positionMs = 45_000L)
        assertTrue(r.isApprox)
    }
    @Test fun `negative gap suppressed`() {
        // clip1 start earlier than clip0 end (clock went backwards)
        val bad = listOf(t0, t0 + 10_000L)
        val r = WallClockTimeline.readoutAt(bad, durs, noApprox, positionMs = 30_000L)
        assertNull(r.gapBeforeMs)   // -20_000 clamped/suppressed
    }
    @Test fun `empty list is total-safe`() {
        val r = WallClockTimeline.readoutAt(emptyList(), emptyList(), emptyList(), positionMs = 0L)
        assertEquals(0L, r.instantMs)
        assertNull(r.gapBeforeMs)
    }
    @Test fun `zero-duration clip does not divide by zero`() {
        val r = WallClockTimeline.readoutAt(listOf(t0, t0 + 5_000L), listOf(0L, 10_000L), listOf(false, false), positionMs = 0L)
        assertEquals(t0 + 5_000L, r.instantMs)  // 0-dur clip0 ends at pos 0 -> select clip1 start
    }
    @Test fun `spansMidnight true across day boundary`() {
        val utc = TimeZone.getTimeZone("UTC")
        // 23:59:50 UTC -> +20s crosses midnight
        val late = 1_700_006_390_000L
        assertTrue(WallClockTimeline.spansMidnight(late, late + 20_000L, utc))
    }
    @Test fun `spansMidnight false within day`() {
        val utc = TimeZone.getTimeZone("UTC")
        assertFalse(WallClockTimeline.spansMidnight(t0, t0 + 60_000L, utc))
    }
}
