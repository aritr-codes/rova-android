package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordSettingBoundsWaitTest {

    @Test fun allowedList_shape() {
        val a = RecordSettingBounds.WAIT_ALLOWED
        assertEquals(62, a.size) // 0, 30, then 1..60 min
        assertEquals(0, a.first()); assertEquals(30, a[1]); assertEquals(60, a[2])
        assertEquals(120, a[3]); assertEquals(3600, a.last())
        assertTrue(a.zipWithNext().all { (x, y) -> y > x })
    }

    @Test fun stepWait_up() {
        assertEquals(30, RecordSettingBounds.stepWait(0, +1))
        assertEquals(60, RecordSettingBounds.stepWait(30, +1))
        assertEquals(120, RecordSettingBounds.stepWait(60, +1))
    }

    @Test fun stepWait_down() {
        assertEquals(60, RecordSettingBounds.stepWait(120, -1))
        assertEquals(30, RecordSettingBounds.stepWait(60, -1))
        assertEquals(0, RecordSettingBounds.stepWait(30, -1))
    }

    @Test fun stepWait_clampsAtEnds() {
        assertEquals(0, RecordSettingBounds.stepWait(0, -1))
        assertEquals(3600, RecordSettingBounds.stepWait(3600, +1))
    }

    @Test fun stepWait_fromOffGrid_snapsThenSteps() {
        assertEquals(60, RecordSettingBounds.stepWait(45, +1)) // 45->30, +1->60
        assertEquals(30, RecordSettingBounds.stepWait(50, -1)) // 50->60, -1->30
    }

    @Test fun nearestAllowedWait_tieRoundsDown_andClamps() {
        assertEquals(30, RecordSettingBounds.nearestAllowedWait(45)) // tie -> lower
        assertEquals(60, RecordSettingBounds.nearestAllowedWait(50))
        assertEquals(0, RecordSettingBounds.nearestAllowedWait(10))
        assertEquals(30, RecordSettingBounds.nearestAllowedWait(20))
        assertEquals(0, RecordSettingBounds.nearestAllowedWait(-5))
        assertEquals(3600, RecordSettingBounds.nearestAllowedWait(9999))
    }

    @Test fun clampWait_snaps() {
        assertEquals(30, RecordSettingBounds.clampWait(45))
        assertEquals(3600, RecordSettingBounds.clampWait(99999))
    }

    @Test fun waitAtMin_atMax() {
        assertTrue(RecordSettingBounds.waitAtMin(0)); assertFalse(RecordSettingBounds.waitAtMin(30))
        assertTrue(RecordSettingBounds.waitAtMax(3600)); assertFalse(RecordSettingBounds.waitAtMax(60))
    }
}
