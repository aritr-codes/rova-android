package com.aritr.rova.service.dualrecord.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CadenceProbeTest {

    @Test fun rejectsNonPowerOfTwoCapacity() {
        assertThrows(IllegalArgumentException::class.java) { CadenceProbe(3) }
        assertThrows(IllegalArgumentException::class.java) { CadenceProbe(0) }
    }

    @Test fun recordThenSnapshot_inOrder_noWrap() {
        val p = CadenceProbe(4)
        p.record(10); p.record(20); p.record(30)
        assertEquals(3, p.recorded())
        assertArrayEquals(longArrayOf(10, 20, 30), p.snapshot())
    }

    @Test fun snapshot_afterWrap_returnsLastCapacityInOrder() {
        val p = CadenceProbe(4)
        for (v in longArrayOf(1, 2, 3, 4, 5, 6)) p.record(v)
        // capacity 4, wrote 6 → newest 4 are 3,4,5,6 oldest→newest
        assertEquals(6, p.recorded())
        assertArrayEquals(longArrayOf(3, 4, 5, 6), p.snapshot())
    }

    @Test fun snapshot_exactlyFull() {
        val p = CadenceProbe(4)
        for (v in longArrayOf(7, 8, 9, 10)) p.record(v)
        assertArrayEquals(longArrayOf(7, 8, 9, 10), p.snapshot())
    }

    @Test fun reset_clearsSamples() {
        val p = CadenceProbe(4)
        p.record(1); p.record(2)
        p.reset()
        assertEquals(0, p.recorded())
        assertArrayEquals(LongArray(0), p.snapshot())
        p.record(99)
        assertArrayEquals(longArrayOf(99), p.snapshot())
    }

    @Test fun emptySnapshot() {
        assertArrayEquals(LongArray(0), CadenceProbe(8).snapshot())
    }
}
