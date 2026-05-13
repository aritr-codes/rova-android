package com.aritr.rova.ui.screens

import com.aritr.rova.ui.components.RecordHudState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordActiveHudFormattersTest {

    // ── loopPillContent ─────────────────────────────────────────────

    @Test fun loop_singleClip_isHidden() {
        assertNull(loopPillContent(loopIndex = 0, loopTotal = 1))
    }

    @Test fun loop_zeroClip_isHidden() {
        // Defensive — loopTotal == 0 shouldn't happen through legitimate session config,
        // but the helper hides the pill rather than rendering "0/0 loops done".
        assertNull(loopPillContent(loopIndex = 0, loopTotal = 0))
    }

    @Test fun loop_indefinite_omitsTotal() {
        assertEquals("3 loops done", loopPillContent(loopIndex = 3, loopTotal = -1))
    }

    @Test fun loop_indefinite_clampsNegativeIndex() {
        assertEquals("0 loops done", loopPillContent(loopIndex = -2, loopTotal = -1))
    }

    @Test fun loop_finite_formatsSlash() {
        assertEquals("4/10 loops done", loopPillContent(loopIndex = 4, loopTotal = 10))
    }

    @Test fun loop_finite_clampsOverflowIndex() {
        assertEquals("10/10 loops done", loopPillContent(loopIndex = 12, loopTotal = 10))
    }

    // ── hudStatusPillContent ────────────────────────────────────────

    @Test fun status_recording_red_with_clip_countdown() {
        val c = hudStatusPillContent(
            state = RecordHudState.Recording,
            clipSecondsLeft = 18, waitSecondsLeft = 0,
        )
        assertEquals(StatusDotColor.RECORDING, c.dot)
        assertEquals("Recording", c.main)
        assertEquals("· 00:18 left", c.time)
    }

    @Test fun status_waiting_amber_with_next_in_countdown() {
        val c = hudStatusPillContent(
            state = RecordHudState.Waiting,
            clipSecondsLeft = 0, waitSecondsLeft = 42,
        )
        assertEquals(StatusDotColor.WAITING, c.dot)
        assertEquals("On break", c.main)
        assertEquals("· next in 00:42", c.time)
    }

    @Test fun status_merging_blue_with_percent() {
        val c = hudStatusPillContent(
            state = RecordHudState.Merging(
                progress = 0.534f, currentSegment = 3, totalSegments = 6,
            ),
            clipSecondsLeft = 0, waitSecondsLeft = 0,
        )
        assertEquals(StatusDotColor.MERGING, c.dot)
        assertEquals("Merging…", c.main)
        assertEquals("· 53%", c.time)
    }

    @Test fun status_merging_zeroProgress_is_0_percent() {
        val c = hudStatusPillContent(
            state = RecordHudState.Merging(
                progress = 0f, currentSegment = 0, totalSegments = 0,
            ),
            clipSecondsLeft = 0, waitSecondsLeft = 0,
        )
        assertEquals("· 0%", c.time)
    }

    @Test fun status_merging_clamps_to_100() {
        // progress > 1 shouldn't happen but the helper should clamp defensively.
        val c = hudStatusPillContent(
            state = RecordHudState.Merging(
                progress = 1.7f, currentSegment = 0, totalSegments = 0,
            ),
            clipSecondsLeft = 0, waitSecondsLeft = 0,
        )
        assertEquals("· 100%", c.time)
    }

    @Test(expected = IllegalStateException::class) fun status_idle_throws_caller_bug() {
        hudStatusPillContent(
            state = RecordHudState.Idle,
            clipSecondsLeft = 0, waitSecondsLeft = 0,
        )
    }
}
