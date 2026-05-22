package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordSettingBoundsTest {

    @Test fun clipStep_is5BelowAMinute_and15AtOrAbove() {
        assertEquals(5, RecordSettingBounds.clipStep(30))
        assertEquals(5, RecordSettingBounds.clipStep(59))
        assertEquals(15, RecordSettingBounds.clipStep(60))
        assertEquals(15, RecordSettingBounds.clipStep(120))
    }

    @Test fun stepClip_incrementsByClipStep_clampedToMax() {
        assertEquals(35, RecordSettingBounds.stepClip(30, +1))
        assertEquals(25, RecordSettingBounds.stepClip(30, -1))
        assertEquals(75, RecordSettingBounds.stepClip(60, +1))
        assertEquals(300, RecordSettingBounds.stepClip(300, +1))
        assertEquals(300, RecordSettingBounds.stepClip(295, +1))
    }

    @Test fun stepClip_clampsToMin() {
        assertEquals(1, RecordSettingBounds.stepClip(1, -1))
        assertEquals(1, RecordSettingBounds.stepClip(3, -1))
    }

    @Test fun stepRepeats_minStepsDownToContinuous() {
        assertEquals(
            RecordSettingBounds.REPEATS_CONTINUOUS,
            RecordSettingBounds.stepRepeats(1, -1),
        )
    }

    @Test fun stepRepeats_continuousStepsUpToMin() {
        assertEquals(1, RecordSettingBounds.stepRepeats(RecordSettingBounds.REPEATS_CONTINUOUS, +1))
    }

    @Test fun stepRepeats_continuousStaysOnFurtherDecrement() {
        assertEquals(
            RecordSettingBounds.REPEATS_CONTINUOUS,
            RecordSettingBounds.stepRepeats(RecordSettingBounds.REPEATS_CONTINUOUS, -1),
        )
    }

    @Test fun stepRepeats_incrementsAndClampsToMax() {
        assertEquals(11, RecordSettingBounds.stepRepeats(10, +1))
        assertEquals(9, RecordSettingBounds.stepRepeats(10, -1))
        assertEquals(999, RecordSettingBounds.stepRepeats(999, +1))
    }

    @Test fun stepWait_incrementsByOne_clamped() {
        assertEquals(6, RecordSettingBounds.stepWait(5, +1))
        assertEquals(4, RecordSettingBounds.stepWait(5, -1))
        assertEquals(0, RecordSettingBounds.stepWait(0, -1))
        assertEquals(60, RecordSettingBounds.stepWait(60, +1))
    }

    @Test fun atBound_helpers() {
        assertTrue(RecordSettingBounds.clipAtMin(1))
        assertTrue(RecordSettingBounds.clipAtMax(300))
        assertFalse(RecordSettingBounds.clipAtMin(30))
        assertTrue(RecordSettingBounds.repeatsAtMin(RecordSettingBounds.REPEATS_CONTINUOUS))
        assertTrue(RecordSettingBounds.repeatsAtMax(999))
        assertFalse(RecordSettingBounds.repeatsAtMin(1))
        assertTrue(RecordSettingBounds.waitAtMin(0))
        assertTrue(RecordSettingBounds.waitAtMax(60))
    }
}
