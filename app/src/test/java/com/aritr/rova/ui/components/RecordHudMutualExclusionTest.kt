package com.aritr.rova.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Slice 3 — pure JVM test for the active-HUD state combinator.
 *
 * Mutual exclusion is enforced at the data layer: there is no instance
 * of [RecordHudState] that represents "both Recording and Waiting at
 * once," so the call site cannot mount both bodies simultaneously.
 * This test pins every (isPeriodicActive, isRecording) ↦ HudState
 * mapping the call site relies on.
 */
class RecordHudMutualExclusionTest {

    @Test
    fun `inactive periodic flag yields Idle regardless of isRecording`() {
        assertEquals(
            RecordHudState.Idle,
            RecordHudState.from(isPeriodicActive = false, isRecording = false)
        )
        // The (!isPeriodicActive && isRecording) combination is
        // technically reachable for a single composition frame during
        // session teardown if `isRecording` lags the periodic flag.
        // It must still resolve to Idle so the active HUD does not
        // flash on screen post-stop.
        assertEquals(
            RecordHudState.Idle,
            RecordHudState.from(isPeriodicActive = false, isRecording = true)
        )
    }

    @Test
    fun `periodic active and recording yields Recording`() {
        assertEquals(
            RecordHudState.Recording,
            RecordHudState.from(isPeriodicActive = true, isRecording = true)
        )
    }

    @Test
    fun `periodic active, not recording, before first clip yields Starting`() {
        // Bug B — the pre-record startup grace (no segment finalized yet)
        // reads as Starting ("Preparing…"), not the inter-clip Waiting.
        assertEquals(
            RecordHudState.Starting,
            RecordHudState.from(
                isPeriodicActive = true,
                isRecording = false,
                segmentCount = 0
            )
        )
    }

    @Test
    fun `periodic active and not recording after a clip yields Waiting`() {
        // Real inter-clip interval (>= 1 segment finalized) stays Waiting.
        assertEquals(
            RecordHudState.Waiting,
            RecordHudState.from(
                isPeriodicActive = true,
                isRecording = false,
                segmentCount = 1
            )
        )
    }

    @Test
    fun `Recording and Waiting are distinct singletons`() {
        // Mutual exclusion: a single state value cannot hold both.
        // The sealed hierarchy makes this assertion structural.
        val recording: RecordHudState = RecordHudState.Recording
        val waiting: RecordHudState = RecordHudState.Waiting
        assertEquals(false, recording == waiting)
    }
}
