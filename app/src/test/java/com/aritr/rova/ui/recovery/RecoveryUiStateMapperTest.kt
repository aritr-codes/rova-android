package com.aritr.rova.ui.recovery

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.recovery.Anomaly
import com.aritr.rova.service.recovery.DiscardEligibility
import com.aritr.rova.service.recovery.SessionClassification
import com.aritr.rova.service.recovery.TerminalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 Slice 2.0.5 — exhaustive table-driven tests for the pure
 * recovery UI mapper.
 *
 * Internal beta correction (smoke 2026-05-03): the mapper now caps
 * cards at one (newest by `terminatedAt` / `startedAt`) and exposes
 * `hiddenCount` for the rest. Tests pin both the eligibility filter
 * and the cap + sort behavior.
 */
class RecoveryUiStateMapperTest {

    // ─── helpers ───────────────────────────────────────────────────

    private fun manifest(
        sessionId: String,
        terminated: Terminated?,
        terminatedAt: Long? = null,
        startedAt: Long = 0L,
        exportState: ExportState = ExportState.NOT_STARTED
    ) = SessionManifest(
        sessionId = sessionId,
        startedAt = startedAt,
        config = SessionConfig(30, 5, "FHD", 4),
        segments = emptyList(),
        exportTier = ExportTier.TIER1_API29_PLUS,
        terminated = terminated,
        terminatedAt = terminatedAt,
        exportState = exportState
    )

    private fun classification(
        sessionId: String,
        eligibility: DiscardEligibility,
        anomalies: List<Anomaly> = emptyList(),
        appendedSegmentFilenames: List<String> = emptyList()
    ) = SessionClassification(
        sessionId = sessionId,
        terminalAction = TerminalAction.ALREADY_TERMINAL,
        eligibility = eligibility,
        anomalies = anomalies,
        appendedSegmentFilenames = appendedSegmentFilenames
    )

    private fun view(
        sessionId: String = "s1",
        terminated: Terminated?,
        eligibility: DiscardEligibility,
        terminatedAt: Long? = null,
        startedAt: Long = 0L,
        anomalies: List<Anomaly> = emptyList(),
        appendedSegmentFilenames: List<String> = emptyList(),
        exportState: ExportState = ExportState.NOT_STARTED
    ) = RecoverySessionView(
        manifest = manifest(sessionId, terminated, terminatedAt, startedAt, exportState),
        classification = classification(
            sessionId, eligibility, anomalies, appendedSegmentFilenames
        )
    )

    private val allTerminators: List<Terminated?> = listOf(
        null,
        Terminated.USER_STOPPED,
        Terminated.COMPLETED,
        Terminated.KILLED_BY_SYSTEM,
        Terminated.KILLED_FORCE_STOP,
        Terminated.MULTI_SEGMENT_KEPT   // Phase 4.3
    )

    private val allEligibilities: List<DiscardEligibility> = listOf(
        DiscardEligibility.AUTO_DISCARD_ELIGIBLE,
        DiscardEligibility.OFFER_DISCARD,
        DiscardEligibility.BLOCKED
    )

    // ─── full matrix sweep ─────────────────────────────────────────

    @Test
    fun `full matrix - exactly the three OFFER_DISCARD non-COMPLETED cells render`() {
        val rendering = mutableListOf<Pair<Terminated?, DiscardEligibility>>()
        val hiding = mutableListOf<Pair<Terminated?, DiscardEligibility>>()

        for (t in allTerminators) {
            for (e in allEligibilities) {
                val ui = RecoveryUiStateMapper.map(
                    listOf(view(terminated = t, eligibility = e))
                )
                if (ui.cards.isEmpty()) hiding += t to e else rendering += t to e
            }
        }

        val expectedRender = setOf(
            Terminated.USER_STOPPED to DiscardEligibility.OFFER_DISCARD,
            Terminated.KILLED_BY_SYSTEM to DiscardEligibility.OFFER_DISCARD,
            Terminated.KILLED_FORCE_STOP to DiscardEligibility.OFFER_DISCARD
        )
        assertEquals(expectedRender, rendering.toSet())
        assertEquals(18 - 3, hiding.size)   // Phase 4.3: 6 terminators × 3 eligibilities − 3 renderable
    }

    // ─── hide branches ────────────────────────────────────────────

    @Test
    fun `null terminated hides regardless of eligibility`() {
        for (e in allEligibilities) {
            val ui = RecoveryUiStateMapper.map(
                listOf(view(terminated = null, eligibility = e))
            )
            assertTrue("eligibility=$e should hide", ui.cards.isEmpty())
            assertEquals(0, ui.hiddenCount)
        }
    }

    @Test
    fun `COMPLETED hides regardless of eligibility`() {
        for (e in allEligibilities) {
            val ui = RecoveryUiStateMapper.map(
                listOf(view(terminated = Terminated.COMPLETED, eligibility = e))
            )
            assertTrue("eligibility=$e should hide", ui.cards.isEmpty())
            assertEquals(0, ui.hiddenCount)
        }
    }

    @Test
    fun `BLOCKED hides for every terminator`() {
        for (t in allTerminators) {
            val ui = RecoveryUiStateMapper.map(
                listOf(view(terminated = t, eligibility = DiscardEligibility.BLOCKED))
            )
            assertTrue("terminator=$t should hide on BLOCKED", ui.cards.isEmpty())
        }
    }

    @Test
    fun `AUTO_DISCARD_ELIGIBLE hides for every terminator`() {
        for (t in allTerminators) {
            val ui = RecoveryUiStateMapper.map(
                listOf(
                    view(
                        terminated = t,
                        eligibility = DiscardEligibility.AUTO_DISCARD_ELIGIBLE
                    )
                )
            )
            assertTrue(
                "terminator=$t should hide on AUTO_DISCARD_ELIGIBLE",
                ui.cards.isEmpty()
            )
        }
    }

    // ─── exportState gate (hotfix 2026-05-08) ─────────────────────

    @Test
    fun `USER_STOPPED + OFFER_DISCARD + FINALIZED hides`() {
        // Hotfix 2026-05-08 — stop-during-wait can finish merge/export
        // while terminated stays USER_STOPPED. The finalized recording
        // is in the gallery; surfacing a "Discard recording" card would
        // mislead the user (Discard wipes the private session dir, not
        // the gallery copy).
        val ui = RecoveryUiStateMapper.map(
            listOf(
                view(
                    sessionId = "s-finalized",
                    terminated = Terminated.USER_STOPPED,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    exportState = ExportState.FINALIZED
                )
            )
        )
        assertEquals(RecoveryUiState.Empty, ui)
    }

    @Test
    fun `KILLED_FORCE_STOP + OFFER_DISCARD + non-finalized still surfaces`() {
        // Force-stop case (smoke d): export pipeline never ran.
        // The card must still surface so the user can clean up the
        // unmerged session residue.
        val ui = RecoveryUiStateMapper.map(
            listOf(
                view(
                    sessionId = "s-forced",
                    terminated = Terminated.KILLED_FORCE_STOP,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    exportState = ExportState.NOT_STARTED
                )
            )
        )
        assertEquals(1, ui.cards.size)
        assertEquals(RecoveryCardKind.KILLED_FORCE_STOP, ui.cards.single().kind)
    }

    @Test
    fun `KILLED_BY_SYSTEM + OFFER_DISCARD + non-finalized still surfaces`() {
        val ui = RecoveryUiStateMapper.map(
            listOf(
                view(
                    sessionId = "s-killed",
                    terminated = Terminated.KILLED_BY_SYSTEM,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    exportState = ExportState.MUXING
                )
            )
        )
        assertEquals(1, ui.cards.size)
        assertEquals(RecoveryCardKind.KILLED_BY_SYSTEM, ui.cards.single().kind)
    }

    @Test
    fun `USER_STOPPED + OFFER_DISCARD + FAILED export still surfaces`() {
        // ADR 0006 §B9: USER_STOPPED + exportState = FAILED is the
        // "Merge failed" recovery path. The user has unmerged segments
        // and no gallery copy; the card must continue to surface.
        val ui = RecoveryUiStateMapper.map(
            listOf(
                view(
                    sessionId = "s-failed",
                    terminated = Terminated.USER_STOPPED,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    exportState = ExportState.FAILED
                )
            )
        )
        assertEquals(1, ui.cards.size)
        assertEquals(RecoveryCardKind.USER_STOPPED, ui.cards.single().kind)
    }

    @Test
    fun `FINALIZED + OFFER_DISCARD hides regardless of terminator`() {
        // Defense-in-depth: any other interrupted-style terminator that
        // somehow arrives with FINALIZED exportState (unexpected today,
        // but the mapper must not surface a discard CTA against a
        // gallery-resident recording).
        for (t in listOf(
            Terminated.USER_STOPPED,
            Terminated.KILLED_BY_SYSTEM,
            Terminated.KILLED_FORCE_STOP
        )) {
            val ui = RecoveryUiStateMapper.map(
                listOf(
                    view(
                        terminated = t,
                        eligibility = DiscardEligibility.OFFER_DISCARD,
                        exportState = ExportState.FINALIZED
                    )
                )
            )
            assertTrue(
                "terminator=$t with FINALIZED should hide",
                ui.cards.isEmpty()
            )
        }
    }

    // ─── render branches ──────────────────────────────────────────

    @Test
    fun `USER_STOPPED + OFFER_DISCARD renders with USER_STOPPED kind and no vendor slot`() {
        val ui = RecoveryUiStateMapper.map(
            listOf(
                view(
                    sessionId = "s-user",
                    terminated = Terminated.USER_STOPPED,
                    eligibility = DiscardEligibility.OFFER_DISCARD
                )
            )
        )
        assertEquals(1, ui.cards.size)
        val card = ui.cards.single()
        assertEquals("s-user", card.sessionId)
        assertEquals(RecoveryCardKind.USER_STOPPED, card.kind)
        assertFalse(card.showVendorHelpSlot)
        assertEquals("Session stopped", card.title)
        // Slice 13C — explicit destructive label.
        assertEquals("Discard recording", card.discardLabel)
    }

    @Test
    fun `KILLED_BY_SYSTEM + OFFER_DISCARD renders with vendor slot true`() {
        val ui = RecoveryUiStateMapper.map(
            listOf(
                view(
                    sessionId = "s-kbs",
                    terminated = Terminated.KILLED_BY_SYSTEM,
                    eligibility = DiscardEligibility.OFFER_DISCARD
                )
            )
        )
        val card = ui.cards.single()
        assertEquals(RecoveryCardKind.KILLED_BY_SYSTEM, card.kind)
        assertTrue(card.showVendorHelpSlot)
        assertEquals("Recording stopped by your device", card.title)
    }

    @Test
    fun `KILLED_FORCE_STOP + OFFER_DISCARD renders with vendor slot false`() {
        val ui = RecoveryUiStateMapper.map(
            listOf(
                view(
                    sessionId = "s-kfs",
                    terminated = Terminated.KILLED_FORCE_STOP,
                    eligibility = DiscardEligibility.OFFER_DISCARD
                )
            )
        )
        val card = ui.cards.single()
        assertEquals(RecoveryCardKind.KILLED_FORCE_STOP, card.kind)
        assertFalse(card.showVendorHelpSlot)
        assertEquals("Recording was force-stopped", card.title)
    }

    // ─── input shape ──────────────────────────────────────────────

    @Test
    fun `empty input yields RecoveryUiState Empty`() {
        val ui = RecoveryUiStateMapper.map(emptyList())
        assertEquals(RecoveryUiState.Empty, ui)
    }

    // ─── cap + sort ───────────────────────────────────────────────

    @Test
    fun `cap to one card emits newest by terminatedAt and counts the rest as hidden`() {
        val ui = RecoveryUiStateMapper.map(
            listOf(
                view(
                    sessionId = "older",
                    terminated = Terminated.USER_STOPPED,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    terminatedAt = 100L
                ),
                view(
                    sessionId = "newest",
                    terminated = Terminated.KILLED_FORCE_STOP,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    terminatedAt = 300L
                ),
                view(
                    sessionId = "middle",
                    terminated = Terminated.KILLED_BY_SYSTEM,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    terminatedAt = 200L
                )
            )
        )
        assertEquals(1, ui.cards.size)
        assertEquals("newest", ui.cards.single().sessionId)
        assertEquals(2, ui.hiddenCount)
    }

    @Test
    fun `terminatedAt null falls back to startedAt for sort`() {
        val ui = RecoveryUiStateMapper.map(
            listOf(
                view(
                    sessionId = "with-terminated",
                    terminated = Terminated.USER_STOPPED,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    terminatedAt = 50L,
                    startedAt = 50L
                ),
                view(
                    sessionId = "no-terminated-but-newer-start",
                    terminated = Terminated.KILLED_FORCE_STOP,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    terminatedAt = null,
                    startedAt = 999L
                )
            )
        )
        assertEquals(1, ui.cards.size)
        assertEquals("no-terminated-but-newer-start", ui.cards.single().sessionId)
        assertEquals(1, ui.hiddenCount)
    }

    @Test
    fun `mixed eligibility + only one renderable cell yields hiddenCount 0`() {
        val ui = RecoveryUiStateMapper.map(
            listOf(
                view(
                    sessionId = "hide-completed",
                    terminated = Terminated.COMPLETED,
                    eligibility = DiscardEligibility.OFFER_DISCARD
                ),
                view(
                    sessionId = "render-user",
                    terminated = Terminated.USER_STOPPED,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    terminatedAt = 10L
                ),
                view(
                    sessionId = "hide-blocked",
                    terminated = Terminated.KILLED_BY_SYSTEM,
                    eligibility = DiscardEligibility.BLOCKED
                ),
                view(
                    sessionId = "hide-auto",
                    terminated = Terminated.USER_STOPPED,
                    eligibility = DiscardEligibility.AUTO_DISCARD_ELIGIBLE
                )
            )
        )
        assertEquals(listOf("render-user"), ui.cards.map { it.sessionId })
        assertEquals(0, ui.hiddenCount)
    }

    @Test
    fun `five eligible inputs yield one card and hiddenCount four`() {
        val ui = RecoveryUiStateMapper.map(
            (1..5).map { i ->
                view(
                    sessionId = "s$i",
                    terminated = Terminated.USER_STOPPED,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    terminatedAt = i * 10L
                )
            }
        )
        assertEquals(1, ui.cards.size)
        assertEquals("s5", ui.cards.single().sessionId)
        assertEquals(4, ui.hiddenCount)
    }

    @Test
    fun `equal recency keys resolve via stable sort - first input wins on tie`() {
        // sortedByDescending uses TimSort which is stable; equal keys
        // preserve input order. Among equal-key entries the first
        // input is the "newest" survivor at index 0 of the sorted
        // descending list.
        val ui = RecoveryUiStateMapper.map(
            listOf(
                view(
                    sessionId = "first",
                    terminated = Terminated.USER_STOPPED,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    terminatedAt = 100L
                ),
                view(
                    sessionId = "second",
                    terminated = Terminated.KILLED_FORCE_STOP,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    terminatedAt = 100L
                )
            )
        )
        assertEquals("first", ui.cards.single().sessionId)
        assertEquals(1, ui.hiddenCount)
    }

    // ─── surviving artifact summaries (visible card only) ─────────

    @Test
    fun `appendedSegmentFilenames produces recovery summary line`() {
        val ui = RecoveryUiStateMapper.map(
            listOf(
                view(
                    terminated = Terminated.KILLED_FORCE_STOP,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    appendedSegmentFilenames = listOf(
                        "segment_0001.mp4",
                        "segment_0002.mp4",
                        "segment_0003.mp4"
                    )
                )
            )
        )
        val card = ui.cards.single()
        assertEquals(1, card.survivingArtifacts.size)
        assertEquals("Recovered 3 segment(s) from disk", card.survivingArtifacts.single())
    }

    @Test
    fun `every Anomaly subtype produces a summary line`() {
        val ui = RecoveryUiStateMapper.map(
            listOf(
                view(
                    terminated = Terminated.USER_STOPPED,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    anomalies = listOf(
                        Anomaly.MissingSegment(listOf(2, 3)),
                        Anomaly.InvalidManifestSegment(listOf(4)),
                        Anomaly.OrphanSegment(listOf(7)),
                        Anomaly.InvalidOrphan(listOf("segment_0008.mp4")),
                        Anomaly.DuplicateSegment(listOf(1)),
                        Anomaly.UnknownArtifact(listOf("debug.log", "extra.bin")),
                        Anomaly.MalformedManifestRecord(listOf("legacy_a.mp4"))
                    )
                )
            )
        )
        val summaries = ui.cards.single().survivingArtifacts
        assertEquals(7, summaries.size)
        assertTrue(summaries.any { it.contains("Missing segment(s) at indices [2, 3]") })
        assertTrue(summaries.any { it.contains("Invalid segment(s) at indices [4]") })
        assertTrue(summaries.any { it.contains("Orphan segment(s) at indices [7]") })
        assertTrue(summaries.any { it.contains("1 unreadable orphan file(s)") })
        assertTrue(summaries.any { it.contains("Duplicate segment(s) at indices [1]") })
        assertTrue(summaries.any { it.contains("2 unknown file(s) in session dir") })
        assertTrue(summaries.any { it.contains("1 malformed manifest record(s)") })
    }

    @Test
    fun `card body matches kind for every renderable terminator`() {
        // Slice 13C — body copy now states three things in order:
        // cause (per kind), status ("recovered segments stay on your
        // device"), and consequence ("This action is permanent").
        // The fragments below pin the cause sentence per kind; the
        // status and consequence are pinned in dedicated tests below.
        for ((t, expectedFragment) in listOf(
            Terminated.USER_STOPPED to "Your last session ended before the segments could merge",
            Terminated.KILLED_BY_SYSTEM to "Your device's battery management",
            Terminated.KILLED_FORCE_STOP to "The app was force-stopped"
        )) {
            val ui = RecoveryUiStateMapper.map(
                listOf(view(terminated = t, eligibility = DiscardEligibility.OFFER_DISCARD))
            )
            val card = ui.cards.single()
            assertNotNull("card for $t", card)
            assertTrue("body for $t contains '$expectedFragment'", card.body.contains(expectedFragment))
            assertTrue("body for $t mentions Discard", card.body.contains("Discard"))
        }
    }

    @Test
    fun `every renderable body explains that recovered segments stay until discarded`() {
        // Slice 13C — beta testers asked what happens to recovered
        // files when they don't tap Discard. The body now says it
        // explicitly so the question doesn't have to surface.
        for (t in listOf(
            Terminated.USER_STOPPED,
            Terminated.KILLED_BY_SYSTEM,
            Terminated.KILLED_FORCE_STOP
        )) {
            val ui = RecoveryUiStateMapper.map(
                listOf(view(terminated = t, eligibility = DiscardEligibility.OFFER_DISCARD))
            )
            val body = ui.cards.single().body
            assertTrue(
                "body for $t should describe segments staying until discard, was: $body",
                body.contains("stay on your device")
            )
        }
    }

    @Test
    fun `every renderable body warns the discard action is permanent`() {
        // Slice 13C — destructive cue. Users should not tap Discard
        // recording without knowing the segments cannot be recovered
        // afterward; the body must call that out in plain words.
        for (t in listOf(
            Terminated.USER_STOPPED,
            Terminated.KILLED_BY_SYSTEM,
            Terminated.KILLED_FORCE_STOP
        )) {
            val ui = RecoveryUiStateMapper.map(
                listOf(view(terminated = t, eligibility = DiscardEligibility.OFFER_DISCARD))
            )
            val body = ui.cards.single().body
            assertTrue(
                "body for $t should mark Discard as permanent, was: $body",
                body.contains("permanent")
            )
        }
    }

    // ─── Phase 4.3 — merge / keepRaw labels ───────────────────────

    @Test
    fun `mapper populates mergeLabel and keepRawLabel when survivingArtifacts non-empty`() {
        val state = RecoveryUiStateMapper.map(
            listOf(
                view(
                    sessionId = "sess-1",
                    terminated = Terminated.USER_STOPPED,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    appendedSegmentFilenames = listOf("seg_0.mp4", "seg_1.mp4"),
                )
            )
        )
        val card = state.cards.single()
        assertEquals("Merge segments", card.mergeLabel)
        assertEquals("Keep as raw clips", card.keepRawLabel)
        assertNull(card.mergeInProgress)
        assertNull(card.mergeFailedReason)
    }

    @Test
    fun `mapper leaves mergeLabel null when no surviving artifacts`() {
        val state = RecoveryUiStateMapper.map(
            listOf(
                view(
                    sessionId = "sess-2",
                    terminated = Terminated.USER_STOPPED,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    appendedSegmentFilenames = emptyList(),
                )
            )
        )
        val card = state.cards.single()
        assertNull(card.mergeLabel)
        assertNull(card.keepRawLabel)
    }

    // ─── Phase 4.3 — MULTI_SEGMENT_KEPT hide rule ─────────────────

    @Test
    fun `MULTI_SEGMENT_KEPT terminated hides the card`() {
        // Phase 4.3 — user chose keep-as-raw-clips; no recovery card
        // should surface. isEligible returns false, map returns Empty.
        val ui = RecoveryUiStateMapper.map(
            listOf(
                view(
                    sessionId = "sess-msk",
                    terminated = Terminated.MULTI_SEGMENT_KEPT,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    appendedSegmentFilenames = listOf("seg_0.mp4")
                )
            )
        )
        assertEquals("MULTI_SEGMENT_KEPT must not surface a card", RecoveryUiState.Empty, ui)
    }
}
