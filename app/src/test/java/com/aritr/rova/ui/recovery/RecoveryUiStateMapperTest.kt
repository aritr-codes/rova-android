package com.aritr.rova.ui.recovery

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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 Slice 2.0.5 — exhaustive table-driven tests for the pure
 * recovery UI mapper.
 *
 * Matrix: `terminated ∈ {null, USER_STOPPED, COMPLETED, KILLED_BY_SYSTEM,
 * KILLED_FORCE_STOP}` × `eligibility ∈ {AUTO_DISCARD_ELIGIBLE,
 * OFFER_DISCARD, BLOCKED}` = 15 cells. Exactly 3 cells render cards
 * (the three non-COMPLETED terminators × OFFER_DISCARD); the other
 * 12 hide.
 *
 * No Robolectric / Compose / Android dependencies — pure JVM.
 */
class RecoveryUiStateMapperTest {

    // ─── helpers ───────────────────────────────────────────────────

    private fun manifest(
        sessionId: String,
        terminated: Terminated?
    ) = SessionManifest(
        sessionId = sessionId,
        startedAt = 0L,
        config = SessionConfig(30, 5, "FHD", 4),
        segments = emptyList(),
        exportTier = ExportTier.TIER1_API29_PLUS,
        terminated = terminated
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
        anomalies: List<Anomaly> = emptyList(),
        appendedSegmentFilenames: List<String> = emptyList()
    ) = RecoverySessionView(
        manifest = manifest(sessionId, terminated),
        classification = classification(
            sessionId, eligibility, anomalies, appendedSegmentFilenames
        )
    )

    private val allTerminators: List<Terminated?> = listOf(
        null,
        Terminated.USER_STOPPED,
        Terminated.COMPLETED,
        Terminated.KILLED_BY_SYSTEM,
        Terminated.KILLED_FORCE_STOP
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
        assertEquals(15 - 3, hiding.size)
    }

    // ─── hide branches ────────────────────────────────────────────

    @Test
    fun `null terminated hides regardless of eligibility`() {
        for (e in allEligibilities) {
            val ui = RecoveryUiStateMapper.map(
                listOf(view(terminated = null, eligibility = e))
            )
            assertTrue("eligibility=$e should hide", ui.cards.isEmpty())
        }
    }

    @Test
    fun `COMPLETED hides regardless of eligibility`() {
        for (e in allEligibilities) {
            val ui = RecoveryUiStateMapper.map(
                listOf(view(terminated = Terminated.COMPLETED, eligibility = e))
            )
            assertTrue("eligibility=$e should hide", ui.cards.isEmpty())
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
        assertEquals("Merge what was recorded", card.mergeLabel)
        assertEquals("Discard", card.discardLabel)
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

    @Test
    fun `card order preserves input order`() {
        val ui = RecoveryUiStateMapper.map(
            listOf(
                view(
                    sessionId = "first",
                    terminated = Terminated.USER_STOPPED,
                    eligibility = DiscardEligibility.OFFER_DISCARD
                ),
                view(
                    sessionId = "second",
                    terminated = Terminated.KILLED_BY_SYSTEM,
                    eligibility = DiscardEligibility.OFFER_DISCARD
                ),
                view(
                    sessionId = "third",
                    terminated = Terminated.KILLED_FORCE_STOP,
                    eligibility = DiscardEligibility.OFFER_DISCARD
                )
            )
        )
        assertEquals(listOf("first", "second", "third"), ui.cards.map { it.sessionId })
    }

    @Test
    fun `mixed input filters hidden sessions while preserving render order`() {
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
                    eligibility = DiscardEligibility.OFFER_DISCARD
                ),
                view(
                    sessionId = "hide-blocked",
                    terminated = Terminated.KILLED_BY_SYSTEM,
                    eligibility = DiscardEligibility.BLOCKED
                ),
                view(
                    sessionId = "render-kfs",
                    terminated = Terminated.KILLED_FORCE_STOP,
                    eligibility = DiscardEligibility.OFFER_DISCARD
                ),
                view(
                    sessionId = "hide-auto",
                    terminated = Terminated.USER_STOPPED,
                    eligibility = DiscardEligibility.AUTO_DISCARD_ELIGIBLE
                )
            )
        )
        assertEquals(
            listOf("render-user", "render-kfs"),
            ui.cards.map { it.sessionId }
        )
    }

    // ─── surviving artifact summaries ─────────────────────────────

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
    fun `appended segments and anomalies coexist - appended line first`() {
        val ui = RecoveryUiStateMapper.map(
            listOf(
                view(
                    terminated = Terminated.KILLED_BY_SYSTEM,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    appendedSegmentFilenames = listOf("segment_0001.mp4"),
                    anomalies = listOf(Anomaly.UnknownArtifact(listOf("debug.log")))
                )
            )
        )
        val summaries = ui.cards.single().survivingArtifacts
        assertEquals(2, summaries.size)
        assertEquals("Recovered 1 segment(s) from disk", summaries[0])
        assertTrue(summaries[1].contains("1 unknown file(s) in session dir"))
    }

    @Test
    fun `no anomalies and no appended segments yields empty surviving list`() {
        val ui = RecoveryUiStateMapper.map(
            listOf(
                view(
                    terminated = Terminated.USER_STOPPED,
                    eligibility = DiscardEligibility.OFFER_DISCARD
                )
            )
        )
        assertTrue(ui.cards.single().survivingArtifacts.isEmpty())
    }

    // ─── regression guards ────────────────────────────────────────

    @Test
    fun `regression - COMPLETED with OFFER_DISCARD never renders`() {
        // Phase 1.7 finalize writes COMPLETED before discard eligibility
        // is recomputed; a stale OFFER_DISCARD must NOT raise a recovery
        // card after a successful merge.
        val ui = RecoveryUiStateMapper.map(
            listOf(
                view(
                    terminated = Terminated.COMPLETED,
                    eligibility = DiscardEligibility.OFFER_DISCARD
                )
            )
        )
        assertEquals(0, ui.cards.size)
    }

    @Test
    fun `regression - empty list short-circuits to Empty singleton equality`() {
        // Empty input must compare-equal to RecoveryUiState.Empty so the
        // 2.1 wiring layer can short-circuit recomposition.
        assertEquals(RecoveryUiState.Empty, RecoveryUiStateMapper.map(emptyList()))
    }

    @Test
    fun `single OFFER_DISCARD render has all expected string fields non-blank`() {
        val ui = RecoveryUiStateMapper.map(
            listOf(
                view(
                    sessionId = "s-fields",
                    terminated = Terminated.USER_STOPPED,
                    eligibility = DiscardEligibility.OFFER_DISCARD
                )
            )
        )
        val card = ui.cards.single()
        assertTrue(card.title.isNotBlank())
        assertTrue(card.body.isNotBlank())
        assertTrue(card.mergeLabel.isNotBlank())
        assertTrue(card.discardLabel.isNotBlank())
    }
}
