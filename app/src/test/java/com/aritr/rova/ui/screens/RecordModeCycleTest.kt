package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Slice B — pure-JVM cover for the Mode tap-cycle helper. The chip in
 * RecordSettingsCard calls [cycleModeNext] (via RecordViewModel.cycleMode)
 * to advance one step. The cycle order matches the segmented Mode strip:
 * Portrait → Landscape → P+L → Portrait. The defensive `else` arm maps
 * any unknown / corrupted persisted value to "Portrait" so the cycle
 * stays deterministic.
 */
class RecordModeCycleTest {

    @Test
    fun cycleModeNext_portrait_to_landscape() {
        assertEquals("Landscape", cycleModeNext("Portrait"))
    }

    @Test
    fun cycleModeNext_landscape_to_portrait_landscape() {
        assertEquals("PortraitLandscape", cycleModeNext("Landscape"))
    }

    @Test
    fun cycleModeNext_portrait_landscape_wraps_to_portrait() {
        assertEquals("Portrait", cycleModeNext("PortraitLandscape"))
    }

    @Test
    fun cycleModeNext_unknown_string_defaults_to_portrait() {
        assertEquals("Portrait", cycleModeNext(""))
        assertEquals("Portrait", cycleModeNext("garbage"))
        assertEquals("Portrait", cycleModeNext("portrait"))   // case-sensitive
    }
}
