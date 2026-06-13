package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordSettingsFormatTest {
    @Test fun tileSummary_secondsClipFiniteLoop() {
        assertEquals("30 s · ×20 · FHD", presetTileSummary(30, 20, "FHD"))
    }

    @Test fun tileSummary_minuteClip() {
        assertEquals("1 m · ×60 · FHD", presetTileSummary(60, 60, "FHD"))
    }

    @Test fun tileSummary_continuousShowsInfinity() {
        assertEquals("1 m · ∞ · HD", presetTileSummary(60, -1, "HD"))
    }

    @Test fun tileSummary_quickSample() {
        assertEquals("10 s · ×3 · HD", presetTileSummary(10, 3, "HD"))
    }
}
