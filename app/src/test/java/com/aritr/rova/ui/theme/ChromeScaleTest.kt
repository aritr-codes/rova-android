package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ChromeScaleTest {

    @Test
    fun referenceDevice_isUnity() {
        // 411dp reference device → factor pinned to 1.0 by construction.
        assertEquals(1.0f, ChromeScale.factor(411f), 0.0001f)
    }

    @Test
    fun snapBand_pinsNeighboursToUnity() {
        // 410/412 (integer rounding of the ~411.4dp true short side) snap to 1.0
        // so the reference device's geometry is byte-identical regardless of rounding.
        assertEquals(1.0f, ChromeScale.factor(410f), 0.0001f)
        assertEquals(1.0f, ChromeScale.factor(412f), 0.0001f)
        // Just outside the band scales normally (413/411 = 1.00487).
        assertEquals(1.00487f, ChromeScale.factor(413f), 0.0005f)
    }

    @Test
    fun narrowPhone_clampsToFloor() {
        // 360/411 = 0.8759 → just below MIN_FACTOR → clamped up.
        assertEquals(ChromeScale.MIN_FACTOR, ChromeScale.factor(360f), 0.0001f)
    }

    @Test
    fun verySmallScreen_clampsToFloor() {
        // 320/411 = 0.7786 → well below MIN_FACTOR.
        assertEquals(ChromeScale.MIN_FACTOR, ChromeScale.factor(320f), 0.0001f)
    }

    @Test
    fun tablet_clampsToCeiling() {
        // 600/411 = 1.4599 → above MAX_FACTOR → clamped down.
        assertEquals(ChromeScale.MAX_FACTOR, ChromeScale.factor(600f), 0.0001f)
    }

    @Test
    fun midRange_passesThrough() {
        // 450/411 = 1.09489 → inside [MIN_FACTOR, MAX_FACTOR], unclamped.
        assertEquals(1.09489f, ChromeScale.factor(450f), 0.0005f)
    }
}
