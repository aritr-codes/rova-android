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
        // [15,30] excluded (lower 15 < 24); only [30,30] qualifies in pass 1
        assertEquals(30 to 30, choose(15 to 30, 30 to 30))
    }

    @Test fun pass2_picksHighestUpperThenLowestLower_whenNoCeilingUpper() {
        // no upper==30 → pass 2: lower>=24 && upper<=30 → [24,24]
        assertEquals(24 to 24, choose(15 to 24, 24 to 24))
    }

    @Test fun pass2_discriminatesSortKeys_highestUpperThenLowestLower() {
        // both candidates in pass 2 (no upper==30); proves the comparator, not just membership
        assertEquals(24 to 25, choose(24 to 24, 24 to 25)) // highest upper wins
        assertEquals(24 to 25, choose(25 to 25, 24 to 25)) // upper tie (25) → lowest lower wins
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
