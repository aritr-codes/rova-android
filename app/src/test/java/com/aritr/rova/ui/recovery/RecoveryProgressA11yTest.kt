package com.aritr.rova.ui.recovery

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [recoveryProgressContentDescription] — the screen-reader
 * label for the RecoveryCard progress strip (WCAG 2.2 AA SC 4.1.2, ADR-0020;
 * audit RECOV-16). The strip's filled/empty cells are decorative `Box`es with
 * no text, so without this the count is invisible to TalkBack.
 */
class RecoveryProgressA11yTest {

    @Test
    fun `merging announces filled of total segments`() {
        assertEquals(
            "Merging: 3 of 8 segments.",
            recoveryProgressContentDescription(cellCount = 8, filledCells = 3, merging = true),
        )
    }

    @Test
    fun `idle announces all clips recovered`() {
        assertEquals(
            "8 clips recovered.",
            recoveryProgressContentDescription(cellCount = 8, filledCells = 8, merging = false),
        )
    }

    @Test
    fun `single clip is not pluralised`() {
        assertEquals(
            "1 clip recovered.",
            recoveryProgressContentDescription(cellCount = 1, filledCells = 1, merging = false),
        )
    }
}
