package com.aritr.rova.service.dualrecord.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CadenceStatsTest {

    @Test fun summarize_oddCount_medianIsMiddle() {
        val s = CadenceStats.summarize(longArrayOf(30, 10, 50, 20, 40))
        assertEquals(5, s.count)
        assertEquals(30, s.medianNs) // sorted 10,20,30,40,50 → index 2
        assertEquals(10, s.minNs)
        assertEquals(50, s.maxNs)
    }

    @Test fun summarize_evenCount_medianIsUpperMiddle() {
        // sorted 10,20,30,40 → size/2 = 2 → 30 (upper-middle, documented convention)
        val s = CadenceStats.summarize(longArrayOf(40, 10, 30, 20))
        assertEquals(30, s.medianNs)
    }

    @Test fun summarize_p95_nearestRank() {
        // 1..100 → p95 nearest-rank index = ceil(0.95*100)-1 = 94 → value 95
        val vals = LongArray(100) { (it + 1).toLong() }
        val s = CadenceStats.summarize(vals)
        assertEquals(95, s.p95Ns)
    }

    @Test fun summarize_empty_allZero() {
        val s = CadenceStats.summarize(LongArray(0))
        assertEquals(0, s.count)
        assertEquals(0, s.medianNs); assertEquals(0, s.p95Ns)
        assertEquals(0, s.minNs); assertEquals(0, s.maxNs)
    }

    @Test fun summarize_single() {
        val s = CadenceStats.summarize(longArrayOf(42))
        assertEquals(1, s.count); assertEquals(42, s.medianNs)
        assertEquals(42, s.p95Ns); assertEquals(42, s.minNs); assertEquals(42, s.maxNs)
    }

    @Test fun deltas_successivePositive() {
        val raw = longArrayOf(100, 133, 166, 216) // deltas 33,33,50
        assertArrayEquals(longArrayOf(33, 33, 50), CadenceStats.deltas(raw, 0, 4))
    }

    @Test fun deltas_skipsNonIncreasing_resetOrDup() {
        // 100→133 (33), 133→133 dup (skip), 133→90 reset (skip), 90→120 (30)
        val raw = longArrayOf(100, 133, 133, 90, 120)
        assertArrayEquals(longArrayOf(33, 30), CadenceStats.deltas(raw, 0, 5))
    }

    @Test fun deltas_honorsFromAndCount_warmupSkip() {
        // skip first 2 warm-up samples; window = [166,216,250] → deltas 50,34
        val raw = longArrayOf(0, 99, 166, 216, 250)
        assertArrayEquals(longArrayOf(50, 34), CadenceStats.deltas(raw, 2, 3))
    }

    @Test fun deltas_tooShort_empty() {
        assertArrayEquals(LongArray(0), CadenceStats.deltas(longArrayOf(5), 0, 1))
        assertArrayEquals(LongArray(0), CadenceStats.deltas(LongArray(0), 0, 0))
    }
}
