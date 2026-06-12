package com.aritr.rova.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for [DualShotPortraitGate] — the ADR-0029 rule that the P+L
 * camera rebind is deferred until the window is portrait (codex review 2026-06-10).
 */
class DualShotPortraitGateTest {

    @Test
    fun landscapeToPL_defers() {
        // The bug case: P+L picked from a landscape window must wait for portrait.
        assertTrue(DualShotPortraitGate.shouldDefer("PortraitLandscape", isPortrait = false))
    }

    @Test
    fun portraitToPL_commitsImmediately() {
        // Already portrait → no rotation, no surface churn → commit now.
        assertFalse(DualShotPortraitGate.shouldDefer("PortraitLandscape", isPortrait = true))
    }

    @Test
    fun singleModes_neverDefer_regardlessOfOrientation() {
        for (mode in listOf("Portrait", "Landscape")) {
            assertFalse(DualShotPortraitGate.shouldDefer(mode, isPortrait = false))
            assertFalse(DualShotPortraitGate.shouldDefer(mode, isPortrait = true))
        }
    }

    @Test
    fun constantMatchesModeString() {
        // Guards against the P+L mode string drifting out of sync with the gate.
        assertTrue(DualShotPortraitGate.shouldDefer(DualShotPortraitGate.P_L, isPortrait = false))
    }
}
