package com.aritr.rova.ui.components

import com.aritr.rova.data.QualityPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins the TalkBack copy that QualityOptionSelector + QuickSetChipRow
 * compose. The chip row appends ", selected" / ", not selected" itself,
 * so the helper used at the call site must hand it the base form
 * ("Quality: FHD") — pre-formatting with the full announce string
 * causes a doubled suffix ("Quality: FHD, selected, selected").
 */
class UiCopyTest {

    @Test
    fun `quality chip base description is the bare label without state`() {
        assertEquals("Quality: FHD", UiCopy.qualityChipBaseDescription(QualityPresets.FHD))
        assertEquals("Quality: SD", UiCopy.qualityChipBaseDescription(QualityPresets.SD))
        assertEquals("Quality: HD", UiCopy.qualityChipBaseDescription(QualityPresets.HD))
        assertEquals("Quality: 4K", UiCopy.qualityChipBaseDescription(QualityPresets.UHD))
    }

    @Test
    fun `quality chip base description never carries a selected state suffix`() {
        QualityPresets.PICKER_ORDER.forEach { quality ->
            val base = UiCopy.qualityChipBaseDescription(quality)
            assertFalse(
                "Base description '$base' must not contain ', selected' or ', not selected'",
                base.contains(", selected") || base.contains(", not selected")
            )
        }
    }

    @Test
    fun `qualityChipDescription announces selected for every PICKER_ORDER value`() {
        assertEquals("Quality: SD, selected", UiCopy.qualityChipDescription(QualityPresets.SD, selected = true))
        assertEquals("Quality: HD, selected", UiCopy.qualityChipDescription(QualityPresets.HD, selected = true))
        assertEquals("Quality: FHD, selected", UiCopy.qualityChipDescription(QualityPresets.FHD, selected = true))
        assertEquals("Quality: 4K, selected", UiCopy.qualityChipDescription(QualityPresets.UHD, selected = true))
    }

    @Test
    fun `qualityChipDescription announces not selected for every PICKER_ORDER value`() {
        QualityPresets.PICKER_ORDER.forEach { quality ->
            assertEquals(
                "Quality: $quality, not selected",
                UiCopy.qualityChipDescription(quality, selected = false)
            )
        }
    }

    @Test
    fun `chip row concatenation of base + state matches qualityChipDescription`() {
        // Mirrors what QuickSetChipRow does: "{base}, selected" / "{base}, not selected".
        // Catches the regression where the call site would pre-format the full
        // announce string and the chip row would re-append a state suffix.
        QualityPresets.PICKER_ORDER.forEach { quality ->
            val base = UiCopy.qualityChipBaseDescription(quality)
            assertEquals(
                UiCopy.qualityChipDescription(quality, selected = true),
                "$base, selected"
            )
            assertEquals(
                UiCopy.qualityChipDescription(quality, selected = false),
                "$base, not selected"
            )
        }
    }

    @Test
    fun `Continuous radio descriptor reads as Records until you stop`() {
        assertEquals("Records until you stop", UiCopy.continuousRadioDescription())
    }

    @Test
    fun `None wait chip descriptor reads as No wait between clips`() {
        assertEquals("No wait between clips", UiCopy.noWaitChipDescription())
    }

    @Test
    fun `clip length cell descriptor uses Tap to change`() {
        assertEquals(
            "Clip length: 30 seconds. Tap to change.",
            UiCopy.clipLengthCellDescription(30)
        )
        assertEquals(
            "Clip length: 1 minute. Tap to change.",
            UiCopy.clipLengthCellDescription(60)
        )
    }

    @Test
    fun `wait None cell descriptor never spells zero`() {
        val description = UiCopy.waitNoneCellDescription()
        assertFalse(description.contains("0"))
        assertEquals(
            "Wait between clips: no wait between clips. Tap to change.",
            description
        )
    }

    @Test
    fun `repeats Continuous cell descriptor never spells -1 or infinity`() {
        val description = UiCopy.repeatsContinuousCellDescription()
        assertFalse(description.contains("-1"))
        assertFalse(description.contains("∞"))
        assertEquals(
            "Repeats: records until you stop. Tap to change.",
            description
        )
    }
}
