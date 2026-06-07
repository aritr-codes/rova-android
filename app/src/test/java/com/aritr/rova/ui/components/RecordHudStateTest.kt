package com.aritr.rova.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2.4 (NEW_UI_BACKEND_REPLAN.md §5 row 2.4) — pure JVM tests
 * for the [RecordHudState.from] resolution priority.
 *
 * Pinned invariants:
 *  - Slice 3's two-arg call shape continues to resolve to
 *    Idle/Recording/Waiting (default merge inputs).
 *  - The merge axis wins over the periodic flags so the brief
 *    `isPeriodicActive=false / isMerging=true` window between the
 *    last clip and merge teardown does not flash an Idle frame.
 *  - `currentSegment` is derived from `progress * totalSegments`
 *    and clamped into `[0, totalSegments]`; degenerate inputs are
 *    coerced rather than throwing.
 */
class RecordHudStateTest {

    // ─── Slice 3 baseline (no merge in flight) ────────────────────

    @Test
    fun `from idle when periodic inactive`() {
        assertEquals(RecordHudState.Idle, RecordHudState.from(false, false))
    }

    @Test
    fun `from recording when periodic active and recording`() {
        assertEquals(RecordHudState.Recording, RecordHudState.from(true, true))
    }

    @Test
    fun `from starting when periodic active not recording and no segment yet`() {
        // Bug B — the startup grace before the first clip is finalized.
        // Default segmentCount is 0, so the 2-arg shape resolves to Starting.
        assertEquals(RecordHudState.Starting, RecordHudState.from(true, false))
    }

    @Test
    fun `from starting on first-segment retry`() {
        // Bug B — a first-segment retry also has segmentCount 0 (nothing
        // finalized yet), so it shows Starting, not the inter-clip Waiting.
        assertEquals(
            RecordHudState.Starting,
            RecordHudState.from(isPeriodicActive = true, isRecording = false, segmentCount = 0),
        )
    }

    @Test
    fun `from waiting once a segment is finalized`() {
        // Bug B — after the first clip lands (segmentCount >= 1) the inter-clip
        // gap is the real "On break" state.
        assertEquals(
            RecordHudState.Waiting,
            RecordHudState.from(isPeriodicActive = true, isRecording = false, segmentCount = 1),
        )
    }

    @Test
    fun `from idle ignores recording when periodic inactive`() {
        // isRecording is the live VideoRecording flag; periodic
        // inactive means we already left the session loop.
        assertEquals(RecordHudState.Idle, RecordHudState.from(false, true))
    }

    // ─── Phase 2.4 — merge axis ───────────────────────────────────

    @Test
    fun `from merging beats recording when isMerging is true`() {
        val state = RecordHudState.from(
            isPeriodicActive = true,
            isRecording = true,
            isMerging = true,
            mergeProgress = 0.5f,
            segmentCount = 4
        )
        assertEquals(
            RecordHudState.Merging(progress = 0.5f, currentSegment = 2, totalSegments = 4),
            state
        )
    }

    @Test
    fun `from merging beats waiting when isMerging is true`() {
        val state = RecordHudState.from(
            isPeriodicActive = true,
            isRecording = false,
            isMerging = true,
            mergeProgress = 0.5f,
            segmentCount = 4
        )
        assertTrue(state is RecordHudState.Merging)
    }

    @Test
    fun `from merging when periodic flipped off but merge still in flight`() {
        // Service teardown sequence: _serviceState.isPeriodicActive
        // can fall before `isMerging` does. The merge axis must win
        // so the HUD does not flash an Idle frame between the last
        // clip and the merge card.
        val state = RecordHudState.from(
            isPeriodicActive = false,
            isRecording = false,
            isMerging = true,
            mergeProgress = 1f,
            segmentCount = 6
        )
        assertEquals(
            RecordHudState.Merging(progress = 1f, currentSegment = 6, totalSegments = 6),
            state
        )
    }

    @Test
    fun `from merging clamps negative progress to zero`() {
        val state = RecordHudState.from(
            isPeriodicActive = true,
            isRecording = false,
            isMerging = true,
            mergeProgress = -0.5f,
            segmentCount = 4
        ) as RecordHudState.Merging
        assertEquals(0f, state.progress, 0.0001f)
        assertEquals(0, state.currentSegment)
        assertEquals(4, state.totalSegments)
    }

    @Test
    fun `from merging clamps progress above one to one`() {
        val state = RecordHudState.from(
            isPeriodicActive = true,
            isRecording = false,
            isMerging = true,
            mergeProgress = 1.5f,
            segmentCount = 4
        ) as RecordHudState.Merging
        assertEquals(1f, state.progress, 0.0001f)
        assertEquals(4, state.currentSegment)
        assertEquals(4, state.totalSegments)
    }

    @Test
    fun `from merging coerces negative segment count to zero`() {
        val state = RecordHudState.from(
            isPeriodicActive = true,
            isRecording = false,
            isMerging = true,
            mergeProgress = 0.5f,
            segmentCount = -3
        ) as RecordHudState.Merging
        assertEquals(0, state.totalSegments)
        assertEquals(0, state.currentSegment)
    }

    @Test
    fun `from merging with zero segments yields zero current`() {
        // Defensive — performMerge skips entirely when segments are
        // empty (RovaRecordingService.kt#L1932-1939), so this
        // shape should never reach the UI; coerce instead of crash.
        val state = RecordHudState.from(
            isPeriodicActive = true,
            isRecording = false,
            isMerging = true,
            mergeProgress = 0.5f,
            segmentCount = 0
        ) as RecordHudState.Merging
        assertEquals(0, state.totalSegments)
        assertEquals(0, state.currentSegment)
    }

    @Test
    fun `from merging beats starting when isMerging and no segment yet`() {
        // Bug A + B — an early user-stop in the startup grace: segmentCount 0
        // AND isMerging true. The merge axis must win (not Starting/Idle).
        val state = RecordHudState.from(
            isPeriodicActive = false,
            isRecording = false,
            isMerging = true,
            mergeProgress = 0.5f,
            segmentCount = 0,
            mergeClipCount = 1,
        )
        assertTrue(state is RecordHudState.Merging)
    }

    // ─── Bug A — mergeClipCount feeds the merge band total ─────────

    @Test
    fun `from merging uses mergeClipCount when segmentCount is zero`() {
        // Early user-stop: the loop never completed an iteration so segmentCount
        // is 0, but the partial clip is being merged — performMerge publishes
        // mergeClipCount=1 so the band reads "1" not "0".
        val state = RecordHudState.from(
            isPeriodicActive = false,
            isRecording = false,
            isMerging = true,
            mergeProgress = 1f,
            segmentCount = 0,
            mergeClipCount = 1,
        ) as RecordHudState.Merging
        assertEquals(1, state.totalSegments)
        assertEquals(1, state.currentSegment)
    }

    @Test
    fun `from merging falls back to segmentCount when mergeClipCount is zero`() {
        // Loop-exhaust path: mergeClipCount unset (0) → segmentCount is the total.
        val state = RecordHudState.from(
            isPeriodicActive = true,
            isRecording = false,
            isMerging = true,
            mergeProgress = 1f,
            segmentCount = 3,
            mergeClipCount = 0,
        ) as RecordHudState.Merging
        assertEquals(3, state.totalSegments)
        assertEquals(3, state.currentSegment)
    }

    @Test
    fun `from merging derives current segment from progress fraction`() {
        // Progress 0.0 → 0 of 6, 0.5 → 3 of 6, 1.0 → 6 of 6.
        // Mid-fraction rounds down (toInt) so the digit never
        // overshoots the fractional progress visible on the bar.
        val zero = RecordHudState.from(true, true, isMerging = true, mergeProgress = 0f, segmentCount = 6)
            as RecordHudState.Merging
        val half = RecordHudState.from(true, true, isMerging = true, mergeProgress = 0.5f, segmentCount = 6)
            as RecordHudState.Merging
        val full = RecordHudState.from(true, true, isMerging = true, mergeProgress = 1f, segmentCount = 6)
            as RecordHudState.Merging
        assertEquals(0, zero.currentSegment)
        assertEquals(3, half.currentSegment)
        assertEquals(6, full.currentSegment)
    }
}
