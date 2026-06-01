package com.aritr.rova.ui.recovery

import com.aritr.rova.R
import com.aritr.rova.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [recoveryProgressContentDescription] — the screen-reader
 * label token for the RecoveryCard progress strip (WCAG 2.2 AA SC 4.1.2,
 * ADR-0020; audit RECOV-16). The strip's filled/empty cells are decorative
 * `Box`es with no text, so without this the count is invisible to TalkBack.
 *
 * B3 i18n task 8 — the helper now returns a resource-backed [UiText] token
 * (resolved at the composable edge) instead of a frozen English String, so
 * these assertions pin the resource id + args (same cases, no case dropped).
 */
class RecoveryProgressA11yTest {

    @Test
    fun `merging announces filled of total segments`() {
        assertEquals(
            UiText.StrArgs(R.string.recovery_progress_cd_merging, listOf(3, 8)),
            recoveryProgressContentDescription(cellCount = 8, filledCells = 3, merging = true),
        )
    }

    @Test
    fun `idle announces all clips recovered`() {
        assertEquals(
            UiText.Plural(R.plurals.recovery_progress_cd_recovered, 8, listOf(8)),
            recoveryProgressContentDescription(cellCount = 8, filledCells = 8, merging = false),
        )
    }

    @Test
    fun `single clip is not pluralised`() {
        assertEquals(
            UiText.Plural(R.plurals.recovery_progress_cd_recovered, 1, listOf(1)),
            recoveryProgressContentDescription(cellCount = 1, filledCells = 1, merging = false),
        )
    }
}
