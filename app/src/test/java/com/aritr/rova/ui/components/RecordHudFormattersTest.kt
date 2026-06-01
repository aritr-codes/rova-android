package com.aritr.rova.ui.components

import com.aritr.rova.R
import com.aritr.rova.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Slice 3 — pure JVM tests for the active-HUD formatting helpers.
 *
 * B3 i18n task 2b: user-facing helpers now return [UiText] tokens, so assertions
 * check the resource id + args (or null) rather than localized English. The exact
 * English copy is verified once, at the resource layer (`values/strings.xml`).
 * Pure-numeric helpers (`formatMmSs`, `formatClipProgressNumbers`,
 * `computeClipProgress`) still return `String` and keep their char-for-char asserts.
 */
class RecordHudFormattersTest {

    // ─── formatMmSs ────────────────────────────────────────────────

    @Test
    fun `formatMmSs renders zero as 00 colon 00`() {
        assertEquals("00:00", RecordHudFormatters.formatMmSs(0L))
    }

    @Test
    fun `formatMmSs zero-pads single-digit seconds`() {
        assertEquals("00:07", RecordHudFormatters.formatMmSs(7L))
    }

    @Test
    fun `formatMmSs renders one minute as 01 colon 00`() {
        assertEquals("01:00", RecordHudFormatters.formatMmSs(60L))
    }

    @Test
    fun `formatMmSs renders mockup example 6 minutes 42 seconds`() {
        assertEquals("06:42", RecordHudFormatters.formatMmSs(402L))
    }

    @Test
    fun `formatMmSs rolls into hours past one hour`() {
        assertEquals("1:00:00", RecordHudFormatters.formatMmSs(3600L))
        assertEquals("1:00:01", RecordHudFormatters.formatMmSs(3601L))
        assertEquals("2:34:56", RecordHudFormatters.formatMmSs(2L * 3600 + 34 * 60 + 56))
    }

    @Test
    fun `formatMmSs clamps negatives to zero`() {
        assertEquals("00:00", RecordHudFormatters.formatMmSs(-5L))
    }

    // ─── formatLoopPosition ───────────────────────────────────────

    @Test
    fun `formatLoopPosition renders fixed loop position`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_loop_position, listOf(4, 10)),
            RecordHudFormatters.formatLoopPosition(4, 10)
        )
    }

    @Test
    fun `formatLoopPosition renders continuous as Loop X without slash`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_loop_position_continuous, listOf(4)),
            RecordHudFormatters.formatLoopPosition(4, -1)
        )
    }

    @Test
    fun `formatLoopPosition clamps negative current to zero`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_loop_position, listOf(0, 10)),
            RecordHudFormatters.formatLoopPosition(-3, 10)
        )
    }

    // ─── formatLoopsRemaining ─────────────────────────────────────

    @Test
    fun `formatLoopsRemaining renders continuous-safe copy`() {
        assertEquals(
            UiText.Str(R.string.record_hud_loops_remaining_continuous),
            RecordHudFormatters.formatLoopsRemaining(currentLoop = 4, totalLoops = -1)
        )
    }

    @Test
    fun `formatLoopsRemaining renders mockup example`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_loops_remaining, listOf(5, 10)),
            RecordHudFormatters.formatLoopsRemaining(currentLoop = 5, totalLoops = 10)
        )
    }

    @Test
    fun `formatLoopsRemaining handles singular`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_loops_remaining, listOf(1, 10)),
            RecordHudFormatters.formatLoopsRemaining(currentLoop = 9, totalLoops = 10)
        )
    }

    @Test
    fun `formatLoopsRemaining handles zero remaining`() {
        assertEquals(
            UiText.Str(R.string.record_hud_loops_remaining_last),
            RecordHudFormatters.formatLoopsRemaining(currentLoop = 10, totalLoops = 10)
        )
        // currentLoop temporarily exceeds totalLoops at clip-roll-over —
        // the helper must not negate the count.
        assertEquals(
            UiText.Str(R.string.record_hud_loops_remaining_last),
            RecordHudFormatters.formatLoopsRemaining(currentLoop = 11, totalLoops = 10)
        )
    }

    // ─── formatNextClipDurationLabel ──────────────────────────────

    @Test
    fun `formatNextClipDurationLabel renders sub-minute as seconds`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_next_clip_seconds, listOf(30)),
            RecordHudFormatters.formatNextClipDurationLabel(30)
        )
    }

    @Test
    fun `formatNextClipDurationLabel renders 60 seconds as 1 m`() {
        assertEquals(
            UiText.Str(R.string.record_hud_next_clip_one_minute),
            RecordHudFormatters.formatNextClipDurationLabel(60)
        )
    }

    @Test
    fun `formatNextClipDurationLabel renders multi-minute clean`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_next_clip_minutes, listOf(5)),
            RecordHudFormatters.formatNextClipDurationLabel(300)
        )
    }

    @Test
    fun `formatNextClipDurationLabel falls back to seconds for non-round`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_next_clip_seconds, listOf(90)),
            RecordHudFormatters.formatNextClipDurationLabel(90)
        )
    }

    // ─── formatRecordingMeta ──────────────────────────────────────

    @Test
    fun `formatRecordingMeta combines quality and flash with separator`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_recording_meta, listOf("FHD", "Flash off")),
            RecordHudFormatters.formatRecordingMeta("FHD", "Flash off")
        )
    }

    // ─── formatFlashLabel ─────────────────────────────────────────

    @Test
    fun `formatFlashLabel maps modes to user-facing copy`() {
        assertEquals(UiText.Str(R.string.record_hud_flash_off), RecordHudFormatters.formatFlashLabel(0))
        assertEquals(UiText.Str(R.string.record_hud_flash_on), RecordHudFormatters.formatFlashLabel(1))
        assertEquals(UiText.Str(R.string.record_hud_flash_auto), RecordHudFormatters.formatFlashLabel(2))
        // Unknown mode collapses to Off so the HUD never blanks the line.
        assertEquals(UiText.Str(R.string.record_hud_flash_off), RecordHudFormatters.formatFlashLabel(99))
    }

    // ─── formatElapsedAnnouncement ────────────────────────────────

    @Test
    fun `formatElapsedAnnouncement uses minutes-only copy under one hour`() {
        assertEquals(
            UiText.Str(R.string.record_hud_elapsed_just_started),
            RecordHudFormatters.formatElapsedAnnouncement(0L)
        )
        assertEquals(
            UiText.Str(R.string.record_hud_elapsed_just_started),
            RecordHudFormatters.formatElapsedAnnouncement(30L)
        )
        assertEquals(
            UiText.StrArgs(R.string.record_hud_elapsed_minute, listOf(1L)),
            RecordHudFormatters.formatElapsedAnnouncement(60L)
        )
        assertEquals(
            UiText.StrArgs(R.string.record_hud_elapsed_minutes, listOf(6L)),
            RecordHudFormatters.formatElapsedAnnouncement(402L)
        )
    }

    @Test
    fun `formatElapsedAnnouncement carries hours past one hour`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_elapsed_hour, listOf(1L)),
            RecordHudFormatters.formatElapsedAnnouncement(3600L)
        )
        assertEquals(
            UiText.StrArgs(R.string.record_hud_elapsed_hour_minutes, listOf(1L, 30L)),
            RecordHudFormatters.formatElapsedAnnouncement(3600L + 1800L)
        )
        assertEquals(
            UiText.StrArgs(R.string.record_hud_elapsed_hours, listOf(2L)),
            RecordHudFormatters.formatElapsedAnnouncement(2L * 3600L)
        )
        assertEquals(
            UiText.StrArgs(R.string.record_hud_elapsed_hours_minute, listOf(2L, 1L)),
            RecordHudFormatters.formatElapsedAnnouncement(2L * 3600L + 60L)
        )
    }

    // ─── formatClipProgressNumbers ────────────────────────────────

    @Test
    fun `formatClipProgressNumbers pads two digits when total is at least 10`() {
        assertEquals(
            "07 / 30 s",
            RecordHudFormatters.formatClipProgressNumbers(elapsedSeconds = 7, totalSeconds = 30)
        )
    }

    @Test
    fun `formatClipProgressNumbers does not pad single-digit total`() {
        assertEquals(
            "3 / 5 s",
            RecordHudFormatters.formatClipProgressNumbers(elapsedSeconds = 3, totalSeconds = 5)
        )
    }

    @Test
    fun `formatClipProgressNumbers clamps elapsed to total ceiling`() {
        // A momentary overshoot during the finalize race must not
        // render "33 / 30 s" — the bar is at 100 %, so are the digits.
        assertEquals(
            "30 / 30 s",
            RecordHudFormatters.formatClipProgressNumbers(elapsedSeconds = 33, totalSeconds = 30)
        )
    }

    // ─── computeClipProgress ──────────────────────────────────────

    @Test
    fun `computeClipProgress is zero on non-positive total`() {
        assertEquals(0f, RecordHudFormatters.computeClipProgress(5, 0), 0.0001f)
        assertEquals(0f, RecordHudFormatters.computeClipProgress(5, -1), 0.0001f)
    }

    @Test
    fun `computeClipProgress is the elapsed-over-total ratio`() {
        assertEquals(0.5f, RecordHudFormatters.computeClipProgress(15, 30), 0.0001f)
    }

    @Test
    fun `computeClipProgress saturates at 1`() {
        assertEquals(1f, RecordHudFormatters.computeClipProgress(45, 30), 0.0001f)
    }

    // ─── Phase 2.4 — formatMergeProgressNumbers ───────────────────

    @Test
    fun `formatMergeProgressNumbers renders mockup example`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_merge_of, listOf(3, 6)),
            RecordHudFormatters.formatMergeProgressNumbers(3, 6)
        )
    }

    @Test
    fun `formatMergeProgressNumbers handles boundary values`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_merge_of, listOf(0, 6)),
            RecordHudFormatters.formatMergeProgressNumbers(0, 6)
        )
        assertEquals(
            UiText.StrArgs(R.string.record_hud_merge_of, listOf(6, 6)),
            RecordHudFormatters.formatMergeProgressNumbers(6, 6)
        )
    }

    @Test
    fun `formatMergeProgressNumbers clamps overshoot to total`() {
        // The export pipeline emits a 1.0 progress tick before the
        // segment count snapshot has been trimmed; the digits must
        // not race past the denominator.
        assertEquals(
            UiText.StrArgs(R.string.record_hud_merge_of, listOf(6, 6)),
            RecordHudFormatters.formatMergeProgressNumbers(7, 6)
        )
    }

    @Test
    fun `formatMergeProgressNumbers coerces negatives to zero`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_merge_of, listOf(0, 4)),
            RecordHudFormatters.formatMergeProgressNumbers(-2, 4)
        )
        assertEquals(
            UiText.StrArgs(R.string.record_hud_merge_of, listOf(0, 0)),
            RecordHudFormatters.formatMergeProgressNumbers(2, -3)
        )
    }

    // ─── Phase 2.4 — formatMergeRemaining ─────────────────────────

    @Test
    fun `formatMergeRemaining renders starting copy at zero progress`() {
        assertEquals(
            UiText.Str(R.string.record_hud_merge_starting),
            RecordHudFormatters.formatMergeRemaining(0f)
        )
    }

    @Test
    fun `formatMergeRemaining hides sub-line at mid-progress`() {
        // Mid-merge, the service emits only fractional progress
        // and no wall-clock estimate; rather than fabricating an
        // ETA, the helper returns null so the band hides the
        // sub-line.
        assertNull(RecordHudFormatters.formatMergeRemaining(0.5f))
    }

    @Test
    fun `formatMergeRemaining renders almost-done copy near completion`() {
        assertEquals(
            UiText.Str(R.string.record_hud_merge_almost),
            RecordHudFormatters.formatMergeRemaining(0.85f)
        )
        assertEquals(
            UiText.Str(R.string.record_hud_merge_almost),
            RecordHudFormatters.formatMergeRemaining(1f)
        )
    }

    @Test
    fun `formatMergeRemaining clamps progress before mapping`() {
        assertEquals(
            UiText.Str(R.string.record_hud_merge_starting),
            RecordHudFormatters.formatMergeRemaining(-0.5f)
        )
        assertEquals(
            UiText.Str(R.string.record_hud_merge_almost),
            RecordHudFormatters.formatMergeRemaining(2f)
        )
    }

    // ─── Phase 2.4 — formatMergeAnnouncement ──────────────────────

    @Test
    fun `formatMergeAnnouncement renders mockup example`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_merge_clip_of, listOf(3, 6)),
            RecordHudFormatters.formatMergeAnnouncement(3, 6)
        )
    }

    @Test
    fun `formatMergeAnnouncement renders preparing copy at zero progress`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_merge_preparing_clips, listOf(6)),
            RecordHudFormatters.formatMergeAnnouncement(0, 6)
        )
    }

    @Test
    fun `formatMergeAnnouncement collapses to preparing for empty total`() {
        assertEquals(
            UiText.Str(R.string.record_hud_merge_preparing),
            RecordHudFormatters.formatMergeAnnouncement(0, 0)
        )
    }

    @Test
    fun `formatMergeAnnouncement clamps overshoot`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_merge_clip_of, listOf(6, 6)),
            RecordHudFormatters.formatMergeAnnouncement(7, 6)
        )
    }

    // ─── Phase 2.4 — formatMergeCompleteSummary ───────────────────

    @Test
    fun `formatMergeCompleteSummary renders plural`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_merge_complete_summary_other, listOf(6)),
            RecordHudFormatters.formatMergeCompleteSummary(6)
        )
    }

    @Test
    fun `formatMergeCompleteSummary renders singular`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_merge_complete_summary_one, listOf(1)),
            RecordHudFormatters.formatMergeCompleteSummary(1)
        )
    }

    @Test
    fun `formatMergeCompleteSummary coerces negative to zero plural`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_merge_complete_summary_other, listOf(0)),
            RecordHudFormatters.formatMergeCompleteSummary(-3)
        )
    }

    // ─── SHAR-09 — formatSessionStatusAnnouncement (SC 4.1.3) ─────

    @Test
    fun `formatSessionStatusAnnouncement recording with loop of total`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_session_status_recording_of, listOf(2, 5)),
            RecordHudFormatters.formatSessionStatusAnnouncement(
                isRecording = true, currentLoop = 2, totalLoops = 5,
            )
        )
    }

    @Test
    fun `formatSessionStatusAnnouncement queued when not recording`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_session_status_queued_of, listOf(0, 5)),
            RecordHudFormatters.formatSessionStatusAnnouncement(
                isRecording = false, currentLoop = 0, totalLoops = 5,
            )
        )
    }

    @Test
    fun `formatSessionStatusAnnouncement omits total for non-positive total`() {
        assertEquals(
            UiText.StrArgs(R.string.record_hud_session_status_recording, listOf(3)),
            RecordHudFormatters.formatSessionStatusAnnouncement(
                isRecording = true, currentLoop = 3, totalLoops = 0,
            )
        )
    }
}
