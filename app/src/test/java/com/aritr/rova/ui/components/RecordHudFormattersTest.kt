package com.aritr.rova.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Slice 3 — pure JVM tests for the active-HUD formatting helpers.
 * No Compose, no Android, no service dependencies — every behavior
 * pinned here is plain string/number transformation.
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
        assertEquals("Loop 4 / 10", RecordHudFormatters.formatLoopPosition(4, 10))
    }

    @Test
    fun `formatLoopPosition renders continuous as Loop X without slash`() {
        assertEquals("Loop 4", RecordHudFormatters.formatLoopPosition(4, -1))
    }

    @Test
    fun `formatLoopPosition clamps negative current to zero`() {
        assertEquals("Loop 0 / 10", RecordHudFormatters.formatLoopPosition(-3, 10))
    }

    // ─── formatLoopsRemaining ─────────────────────────────────────

    @Test
    fun `formatLoopsRemaining renders continuous-safe copy`() {
        assertEquals(
            "Records until you stop",
            RecordHudFormatters.formatLoopsRemaining(currentLoop = 4, totalLoops = -1)
        )
    }

    @Test
    fun `formatLoopsRemaining renders mockup example`() {
        assertEquals(
            "5 of 10 loops remaining",
            RecordHudFormatters.formatLoopsRemaining(currentLoop = 5, totalLoops = 10)
        )
    }

    @Test
    fun `formatLoopsRemaining handles singular`() {
        assertEquals(
            "1 of 10 loops remaining",
            RecordHudFormatters.formatLoopsRemaining(currentLoop = 9, totalLoops = 10)
        )
    }

    @Test
    fun `formatLoopsRemaining handles zero remaining`() {
        assertEquals(
            "Last clip in progress",
            RecordHudFormatters.formatLoopsRemaining(currentLoop = 10, totalLoops = 10)
        )
        // currentLoop temporarily exceeds totalLoops at clip-roll-over —
        // the helper must not negate the count.
        assertEquals(
            "Last clip in progress",
            RecordHudFormatters.formatLoopsRemaining(currentLoop = 11, totalLoops = 10)
        )
    }

    // ─── formatNextClipDurationLabel ──────────────────────────────

    @Test
    fun `formatNextClipDurationLabel renders sub-minute as seconds`() {
        assertEquals(
            "Next clip will run for 30 s",
            RecordHudFormatters.formatNextClipDurationLabel(30)
        )
    }

    @Test
    fun `formatNextClipDurationLabel renders 60 seconds as 1 m`() {
        assertEquals(
            "Next clip will run for 1 m",
            RecordHudFormatters.formatNextClipDurationLabel(60)
        )
    }

    @Test
    fun `formatNextClipDurationLabel renders multi-minute clean`() {
        assertEquals(
            "Next clip will run for 5 m",
            RecordHudFormatters.formatNextClipDurationLabel(300)
        )
    }

    @Test
    fun `formatNextClipDurationLabel falls back to seconds for non-round`() {
        assertEquals(
            "Next clip will run for 90 s",
            RecordHudFormatters.formatNextClipDurationLabel(90)
        )
    }

    // ─── formatRecordingMeta ──────────────────────────────────────

    @Test
    fun `formatRecordingMeta combines quality and flash with separator`() {
        assertEquals(
            "FHD · Flash off",
            RecordHudFormatters.formatRecordingMeta("FHD", "Flash off")
        )
    }

    // ─── formatFlashLabel ─────────────────────────────────────────

    @Test
    fun `formatFlashLabel maps modes to user-facing copy`() {
        assertEquals("Flash off", RecordHudFormatters.formatFlashLabel(0))
        assertEquals("Flash on", RecordHudFormatters.formatFlashLabel(1))
        assertEquals("Flash auto", RecordHudFormatters.formatFlashLabel(2))
        // Unknown mode collapses to Off so the HUD never blanks the line.
        assertEquals("Flash off", RecordHudFormatters.formatFlashLabel(99))
    }

    // ─── formatElapsedAnnouncement ────────────────────────────────

    @Test
    fun `formatElapsedAnnouncement uses minutes-only copy under one hour`() {
        assertEquals(
            "Session just started",
            RecordHudFormatters.formatElapsedAnnouncement(0L)
        )
        assertEquals(
            "Session just started",
            RecordHudFormatters.formatElapsedAnnouncement(30L)
        )
        assertEquals(
            "Session elapsed 1 minute",
            RecordHudFormatters.formatElapsedAnnouncement(60L)
        )
        assertEquals(
            "Session elapsed 6 minutes",
            RecordHudFormatters.formatElapsedAnnouncement(402L)
        )
    }

    @Test
    fun `formatElapsedAnnouncement carries hours past one hour`() {
        assertEquals(
            "Session elapsed 1 hour",
            RecordHudFormatters.formatElapsedAnnouncement(3600L)
        )
        assertEquals(
            "Session elapsed 1 hour 30 minutes",
            RecordHudFormatters.formatElapsedAnnouncement(3600L + 1800L)
        )
        assertEquals(
            "Session elapsed 2 hours",
            RecordHudFormatters.formatElapsedAnnouncement(2L * 3600L)
        )
        assertEquals(
            "Session elapsed 2 hours 1 minute",
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
        assertEquals("3 of 6", RecordHudFormatters.formatMergeProgressNumbers(3, 6))
    }

    @Test
    fun `formatMergeProgressNumbers handles boundary values`() {
        assertEquals("0 of 6", RecordHudFormatters.formatMergeProgressNumbers(0, 6))
        assertEquals("6 of 6", RecordHudFormatters.formatMergeProgressNumbers(6, 6))
    }

    @Test
    fun `formatMergeProgressNumbers clamps overshoot to total`() {
        // The export pipeline emits a 1.0 progress tick before the
        // segment count snapshot has been trimmed; the digits must
        // not race past the denominator.
        assertEquals("6 of 6", RecordHudFormatters.formatMergeProgressNumbers(7, 6))
    }

    @Test
    fun `formatMergeProgressNumbers coerces negatives to zero`() {
        assertEquals("0 of 4", RecordHudFormatters.formatMergeProgressNumbers(-2, 4))
        assertEquals("0 of 0", RecordHudFormatters.formatMergeProgressNumbers(2, -3))
    }

    // ─── Phase 2.4 — formatMergeRemaining ─────────────────────────

    @Test
    fun `formatMergeRemaining renders starting copy at zero progress`() {
        assertEquals("Starting merge…", RecordHudFormatters.formatMergeRemaining(0f))
    }

    @Test
    fun `formatMergeRemaining hides sub-line at mid-progress`() {
        // Mid-merge, the service emits only fractional progress
        // and no wall-clock estimate; rather than fabricating an
        // ETA, the helper returns null so the band hides the
        // sub-line.
        assertEquals(null, RecordHudFormatters.formatMergeRemaining(0.5f))
    }

    @Test
    fun `formatMergeRemaining renders almost-done copy near completion`() {
        assertEquals("Almost done…", RecordHudFormatters.formatMergeRemaining(0.85f))
        assertEquals("Almost done…", RecordHudFormatters.formatMergeRemaining(1f))
    }

    @Test
    fun `formatMergeRemaining clamps progress before mapping`() {
        assertEquals("Starting merge…", RecordHudFormatters.formatMergeRemaining(-0.5f))
        assertEquals("Almost done…", RecordHudFormatters.formatMergeRemaining(2f))
    }

    // ─── Phase 2.4 — formatMergeAnnouncement ──────────────────────

    @Test
    fun `formatMergeAnnouncement renders mockup example`() {
        assertEquals(
            "Merging clip 3 of 6",
            RecordHudFormatters.formatMergeAnnouncement(3, 6)
        )
    }

    @Test
    fun `formatMergeAnnouncement renders preparing copy at zero progress`() {
        assertEquals(
            "Preparing to merge 6 clips",
            RecordHudFormatters.formatMergeAnnouncement(0, 6)
        )
    }

    @Test
    fun `formatMergeAnnouncement collapses to preparing for empty total`() {
        assertEquals(
            "Preparing to merge",
            RecordHudFormatters.formatMergeAnnouncement(0, 0)
        )
    }

    @Test
    fun `formatMergeAnnouncement clamps overshoot`() {
        assertEquals(
            "Merging clip 6 of 6",
            RecordHudFormatters.formatMergeAnnouncement(7, 6)
        )
    }

    // ─── Phase 2.4 — formatMergeCompleteSummary ───────────────────

    @Test
    fun `formatMergeCompleteSummary renders plural`() {
        assertEquals(
            "6 clips · saved to Library",
            RecordHudFormatters.formatMergeCompleteSummary(6)
        )
    }

    @Test
    fun `formatMergeCompleteSummary renders singular`() {
        assertEquals(
            "1 clip · saved to Library",
            RecordHudFormatters.formatMergeCompleteSummary(1)
        )
    }

    @Test
    fun `formatMergeCompleteSummary coerces negative to zero plural`() {
        assertEquals(
            "0 clips · saved to Library",
            RecordHudFormatters.formatMergeCompleteSummary(-3)
        )
    }
}
