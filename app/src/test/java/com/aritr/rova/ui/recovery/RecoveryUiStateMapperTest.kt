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
import org.junit.Assert.assertNotNull
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
        startedAt: Long = 0L
    ) = SessionManifest(
        sessionId = sessionId,
        startedAt = startedAt,
        config = SessionConfig(30, 5, "FHD", 4),
        segments = emptyList(),
        exportTier = ExportTier.TIER1_API29_PLUS,
        terminated = terminated,
        terminatedAt = terminatedAt
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
        appendedSegmentFilenames: List<String> = emptyList()
    ) = RecoverySessionView(
        manifest = manifest(sessionId, terminated, terminatedAt, startedAt),
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
        for ((t, expectedFragment) in listOf(
            Terminated.USER_STOPPED to "Your last session ended before merging",
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
}
