package com.aritr.rova.ui.screens.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeModePolicyTest {

    @Test fun compactWidths_getFixedPhysical() {
        assertEquals(ChromeMode.FixedPhysical, chromeMode(smallestScreenWidthDp = 320))
        assertEquals(ChromeMode.FixedPhysical, chromeMode(smallestScreenWidthDp = 411))
        assertEquals(ChromeMode.FixedPhysical, chromeMode(smallestScreenWidthDp = 599))
    }

    @Test fun sw600dpAndUp_getAdaptive() {
        // API 36/37 ignores orientation lock here (spec §9) — Adaptive is mandatory.
        assertEquals(ChromeMode.Adaptive, chromeMode(smallestScreenWidthDp = 600))
        assertEquals(ChromeMode.Adaptive, chromeMode(smallestScreenWidthDp = 840))
    }

    @Test fun lock_requiresAllThreeConditions() {
        assertTrue(RecordChromeLockPolicy.shouldLock(isRecordRoute = true, modalOpen = false, chromeMode = ChromeMode.FixedPhysical))
        assertFalse(RecordChromeLockPolicy.shouldLock(isRecordRoute = false, modalOpen = false, chromeMode = ChromeMode.FixedPhysical))
        // Modal open => unlock (spec §7 — sheet rotates as a normal surface).
        assertFalse(RecordChromeLockPolicy.shouldLock(isRecordRoute = true, modalOpen = true, chromeMode = ChromeMode.FixedPhysical))
        // Large screens never lock (the OS would ignore it anyway).
        assertFalse(RecordChromeLockPolicy.shouldLock(isRecordRoute = true, modalOpen = false, chromeMode = ChromeMode.Adaptive))
    }
}
