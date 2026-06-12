package com.aritr.rova.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for [DualShotPortraitGate] — the ADR-0029 rule that the
 * DualShot camera rebind is deferred until the window is portrait (codex review 2026-06-10).
 */
class DualShotPortraitGateTest {

    @Test
    fun landscapeToDualShot_defers() {
        // The bug case: DualShot picked from a landscape window must wait for portrait.
        assertTrue(DualShotPortraitGate.shouldDefer("DualShot", isPortrait = false))
    }

    @Test
    fun portraitToDualShot_commitsImmediately() {
        // Already portrait → no rotation, no surface churn → commit now.
        assertFalse(DualShotPortraitGate.shouldDefer("DualShot", isPortrait = true))
    }

    @Test
    fun singleModes_neverDefer_regardlessOfOrientation() {
        for (mode in listOf("Single", "FrontBack")) {
            assertFalse(DualShotPortraitGate.shouldDefer(mode, isPortrait = false))
            assertFalse(DualShotPortraitGate.shouldDefer(mode, isPortrait = true))
        }
    }

    @Test
    fun constantMatchesModeString() {
        // Guards against the DualShot mode string drifting out of sync with the gate.
        assertTrue(DualShotPortraitGate.shouldDefer(DualShotPortraitGate.DUAL_SHOT, isPortrait = false))
    }
}
