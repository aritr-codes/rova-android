package com.aritr.rova.service.dualrecord.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AeFpsRangePolicyTest {

    private fun choose(vararg ranges: Pair<Int, Int>) =
        AeFpsRangePolicy.choose(ranges.toList(), floor = 24, ceiling = 30)

    @Test fun prefers24to30_lowestLowerWithCeilingUpper() {
        // pass 1: upper==30 && lower>=24 → {[24,30],[30,30]} → lowest lower
        assertEquals(24 to 30, choose(15 to 15, 15 to 30, 24 to 30, 30 to 30))
    }

    @Test fun fallsBackTo30to30_whenNo24to30() {
        // [15,30] excluded (lower 15 < 24); (30,30) is a pin (excluded from pass 1) so it
        // is the sole pass-2 candidate (lower>=24 && upper<=30) → still lifts the collapse
        assertEquals(30 to 30, choose(15 to 30, 30 to 30))
    }

    @Test fun pass2_fallsBackToPinAtOrAboveFloor() {
        // no upper==30 → pass 2: lower>=24 && upper<=30 → [24,24] (15to24 excluded, lower 15 < 24)
        assertEquals(24 to 24, choose(15 to 24, 24 to 24))
    }

    @Test fun pass2_prefersLowestFpsCapThenLowestLower_forBrightness() {
        // no true ceiling span → pass 2 prefers the LOWEST upper (brightest dim), then lowest lower
        assertEquals(24 to 24, choose(24 to 24, 24 to 28)) // lowest cap wins (24 over 28)
        assertEquals(24 to 28, choose(25 to 28, 24 to 28)) // cap tie (28) → lowest lower wins
    }

    @Test fun realDevice_noTrueSpan_picksLowestPinForBrightness() {
        // RZCYA1VBQ2H actual back-camera ranges — no [24,30]; (24,24) beats the (30,30) pin
        assertEquals(
            24 to 24,
            choose(15 to 15, 15 to 20, 20 to 20, 24 to 24, 15 to 30, 30 to 30)
        )
    }

    @Test fun prefersTrueSpanOverCeilingPin() {
        // a real [24,30] span is preferred over the (30,30) pin (pass 1 excludes the pin)
        assertEquals(24 to 30, choose(24 to 30, 30 to 30))
    }

    @Test fun ceilingPinAlone_stillLiftsTheFloor() {
        // only a (30,30) pin available → pass 1 excludes it (lower==upper), pass 2 accepts it
        assertEquals(30 to 30, choose(30 to 30))
    }

    @Test fun nullWhenNoFloorRange() {
        // no lower>=24 anywhere → don't set
        assertNull(choose(7 to 30, 15 to 30))
    }

    @Test fun nullWhenEmpty() {
        assertNull(choose())
    }

    @Test fun nullWhenOnlyHighCeilings() {
        // no upper<=30 with lower>=24 ([30,60],[60,60] exceed ceiling; [15,15] below floor)
        assertNull(choose(15 to 15, 30 to 60, 60 to 60))
    }

    @Test fun duplicatesAreStable() {
        assertEquals(24 to 30, choose(24 to 30, 24 to 30))
    }
}
