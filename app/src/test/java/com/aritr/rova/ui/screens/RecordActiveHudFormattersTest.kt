package com.aritr.rova.ui.screens

import com.aritr.rova.R
import com.aritr.rova.ui.components.RecordHudState
import com.aritr.rova.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B3 i18n task 2 — these helpers now return [UiText] tokens, not localized
 * English. Assertions check the resource id + args/quantity (or null), so they
 * survive Phase B re-translation. The exact English copy is verified once, at
 * the resource layer (`values/strings.xml`), not here.
 */
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
        assertEquals(
            UiText.StrArgs(R.string.record_hud_loops_done_indefinite, listOf(3)),
            loopPillContent(loopIndex = 3, loopTotal = -1),
        )
    }

    @Test fun loop_indefinite_clampsNegativeIndex() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_loops_done_indefinite, listOf(0)),
            loopPillContent(loopIndex = -2, loopTotal = -1),
        )
    }

    @Test fun loop_finite_formatsSlash() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_loops_done, listOf(4, 10)),
            loopPillContent(loopIndex = 4, loopTotal = 10),
        )
    }

    @Test fun loop_finite_clampsOverflowIndex() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_loops_done, listOf(10, 10)),
            loopPillContent(loopIndex = 12, loopTotal = 10),
        )
    }

    // ── hudStatusPillContent ────────────────────────────────────────

    @Test fun status_recording_red_with_clip_countdown() {
        val c = hudStatusPillContent(
            state = RecordHudState.Recording,
            clipSecondsLeft = 18, waitSecondsLeft = 0,
        )
        assertEquals(StatusDotColor.RECORDING, c.dot)
        assertEquals(UiText.Str(R.string.record_hud_status_recording), c.main)
        assertEquals(UiText.StrArgs(R.string.record_hud_time_left, listOf("00:18")), c.time)
    }

    @Test fun status_waiting_amber_with_next_in_countdown() {
        val c = hudStatusPillContent(
            state = RecordHudState.Waiting,
            clipSecondsLeft = 0, waitSecondsLeft = 42,
        )
        assertEquals(StatusDotColor.WAITING, c.dot)
        assertEquals(UiText.Str(R.string.record_hud_status_on_break), c.main)
        assertEquals(UiText.StrArgs(R.string.record_hud_time_next_in, listOf("00:42")), c.time)
    }

    @Test fun status_starting_amber_with_static_caption() {
        // Bug B — startup grace: reuses the WAITING (amber/slate) dot, static
        // caption (no countdown), distinct copy from "On break".
        val c = hudStatusPillContent(
            state = RecordHudState.Starting,
            clipSecondsLeft = 0, waitSecondsLeft = 0,
        )
        assertEquals(StatusDotColor.WAITING, c.dot)
        assertEquals(UiText.Str(R.string.record_hud_status_starting), c.main)
        assertEquals(UiText.Str(R.string.record_hud_starting_caption), c.time)
    }

    @Test fun status_merging_blue_with_percent() {
        val c = hudStatusPillContent(
            state = RecordHudState.Merging(
                progress = 0.534f, currentSegment = 3, totalSegments = 6,
            ),
            clipSecondsLeft = 0, waitSecondsLeft = 0,
        )
        assertEquals(StatusDotColor.MERGING, c.dot)
        assertEquals(UiText.Str(R.string.record_hud_status_merging), c.main)
        assertEquals(UiText.StrArgs(R.string.record_hud_time_percent, listOf(53)), c.time)
    }

    @Test fun status_merging_zeroProgress_is_0_percent() {
        val c = hudStatusPillContent(
            state = RecordHudState.Merging(
                progress = 0f, currentSegment = 0, totalSegments = 0,
            ),
            clipSecondsLeft = 0, waitSecondsLeft = 0,
        )
        assertEquals(UiText.StrArgs(R.string.record_hud_time_percent, listOf(0)), c.time)
    }

    @Test fun status_merging_clamps_to_100() {
        // progress > 1 shouldn't happen but the helper should clamp defensively.
        val c = hudStatusPillContent(
            state = RecordHudState.Merging(
                progress = 1.7f, currentSegment = 0, totalSegments = 0,
            ),
            clipSecondsLeft = 0, waitSecondsLeft = 0,
        )
        assertEquals(UiText.StrArgs(R.string.record_hud_time_percent, listOf(100)), c.time)
    }

    @Test(expected = IllegalStateException::class) fun status_idle_throws_caller_bug() {
        hudStatusPillContent(
            state = RecordHudState.Idle,
            clipSecondsLeft = 0, waitSecondsLeft = 0,
        )
    }

    // ── hudActiveAnnouncement (REC-22, SC 4.1.3) ────────────────────
    // The live-region token must NOT include the per-second countdown,
    // so it changes only on state/loop/merge-segment boundaries.

    @Test fun announce_recording_includesLoopOfTotal() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_announce_recording_loop_of, listOf(2, 5)),
            hudActiveAnnouncement(RecordHudState.Recording, loopIndex = 2, loopTotal = 5),
        )
    }

    @Test fun announce_waiting_includesLoopOfTotal() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_announce_on_break_loop_of, listOf(2, 5)),
            hudActiveAnnouncement(RecordHudState.Waiting, loopIndex = 2, loopTotal = 5),
        )
    }

    @Test fun announce_singleClip_omitsLoopPhrase() {
        assertEquals(
            UiText.Str(R.string.record_hud_announce_recording),
            hudActiveAnnouncement(RecordHudState.Recording, loopIndex = 0, loopTotal = 1),
        )
    }

    @Test fun announce_indefinite_omitsTotal() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_announce_recording_loop, listOf(3)),
            hudActiveAnnouncement(RecordHudState.Recording, loopIndex = 3, loopTotal = -1),
        )
    }

    @Test fun announce_starting_isStaticPhrase_noLoopSuffix() {
        // Bug B — startup grace has no loop position yet; a single static phrase,
        // independent of loopIndex/loopTotal.
        assertEquals(
            UiText.Str(R.string.record_hud_announce_starting),
            hudActiveAnnouncement(RecordHudState.Starting, loopIndex = 5, loopTotal = 9),
        )
    }

    @Test fun announce_merging_usesSegmentAnnouncement_notPercent() {
        // Forwarded verbatim from formatMergeAnnouncement (now externalized to a
        // UiText token; its own token-level test lives in RecordHudFormattersTest).
        assertEquals(
            UiText.StrArgs(R.string.record_hud_merge_clip_of, listOf(3, 6)),
            hudActiveAnnouncement(
                RecordHudState.Merging(progress = 0.5f, currentSegment = 3, totalSegments = 6),
                loopIndex = 0, loopTotal = 6,
            ),
        )
    }

    @Test fun announce_idle_isNull() {
        // Anti-chant "nothing to announce" — formerly "", now null (resolves back
        // to "" at the call site).
        assertNull(hudActiveAnnouncement(RecordHudState.Idle, loopIndex = 0, loopTotal = 0))
    }
}
