package com.aritr.rova.ui.recovery

import com.aritr.rova.R
import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.recovery.Anomaly
import com.aritr.rova.service.recovery.DiscardEligibility
import com.aritr.rova.service.recovery.SessionClassification
import com.aritr.rova.service.recovery.TerminalAction
import com.aritr.rova.ui.text.UiText
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

    /**
     * M3 — the mapper now takes a [RecoveryClock]. Every pre-existing case is time-agnostic,
     * so they all map at [FIXED_NOW]; the recency contract has its own section below.
     */
    private fun mapAt(views: List<RecoverySessionView>, nowMillis: Long = FIXED_NOW) =
        RecoveryUiStateMapper.map(views, RecoveryClock { nowMillis })

    private fun manifest(
        sessionId: String,
        terminated: Terminated?,
        terminatedAt: Long? = null,
        startedAt: Long = 0L,
        exportState: ExportState = ExportState.NOT_STARTED,
        stopReason: StopReason = StopReason.NONE
    ) = SessionManifest(
        sessionId = sessionId,
        startedAt = startedAt,
        config = SessionConfig(30, 5, "FHD", 4),
        segments = emptyList(),
        exportTier = ExportTier.TIER1_API29_PLUS,
        terminated = terminated,
        terminatedAt = terminatedAt,
        exportState = exportState,
        stopReason = stopReason
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
        exportState: ExportState = ExportState.NOT_STARTED,
        stopReason: StopReason = StopReason.NONE
    ) = RecoverySessionView(
        manifest = manifest(sessionId, terminated, terminatedAt, startedAt, exportState, stopReason),
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
                val ui = mapAt(
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
            val ui = mapAt(
                listOf(view(terminated = null, eligibility = e))
            )
            assertTrue("eligibility=$e should hide", ui.cards.isEmpty())
            assertEquals(0, ui.hiddenCount)
        }
    }

    @Test
    fun `COMPLETED hides regardless of eligibility`() {
        for (e in allEligibilities) {
            val ui = mapAt(
                listOf(view(terminated = Terminated.COMPLETED, eligibility = e))
            )
            assertTrue("eligibility=$e should hide", ui.cards.isEmpty())
            assertEquals(0, ui.hiddenCount)
        }
    }

    @Test
    fun `BLOCKED hides for every terminator`() {
        for (t in allTerminators) {
            val ui = mapAt(
                listOf(view(terminated = t, eligibility = DiscardEligibility.BLOCKED))
            )
            assertTrue("terminator=$t should hide on BLOCKED", ui.cards.isEmpty())
        }
    }

    @Test
    fun `AUTO_DISCARD_ELIGIBLE hides for every terminator`() {
        for (t in allTerminators) {
            val ui = mapAt(
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
        val ui = mapAt(
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
        val ui = mapAt(
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
        val ui = mapAt(
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
        val ui = mapAt(
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
            val ui = mapAt(
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
        val ui = mapAt(
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
        assertEquals(R.string.recovery_title_user_stopped, card.titleRes)
        // Slice 13C — explicit destructive label.
        assertEquals(R.string.recovery_discard_label, card.discardLabelRes)
    }

    @Test
    fun `KILLED_BY_SYSTEM + OFFER_DISCARD renders with vendor slot true`() {
        val ui = mapAt(
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
        assertEquals(R.string.recovery_title_killed_by_system, card.titleRes)
    }

    @Test
    fun `KILLED_FORCE_STOP + OFFER_DISCARD renders with vendor slot false`() {
        val ui = mapAt(
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
        assertEquals(R.string.recovery_title_force_stopped, card.titleRes)
    }

    // ─── input shape ──────────────────────────────────────────────

    @Test
    fun `empty input yields RecoveryUiState Empty`() {
        val ui = mapAt(emptyList())
        assertEquals(RecoveryUiState.Empty, ui)
    }

    // ─── cap + sort ───────────────────────────────────────────────

    @Test
    fun `cap to one card emits newest by terminatedAt and counts the rest as hidden`() {
        val ui = mapAt(
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
        val ui = mapAt(
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
        val ui = mapAt(
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
        val ui = mapAt(
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
        val ui = mapAt(
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
        val ui = mapAt(
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
        assertEquals(
            UiText.StrArgs(R.string.recovery_artifact_recovered_segments, listOf(3)),
            card.survivingArtifacts.single(),
        )
    }

    @Test
    fun `every Anomaly subtype produces a summary line`() {
        val ui = mapAt(
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
        // B3 i18n task 8 — assertions pin the resource id + args (index lists
        // pass through as the List.toString() %s arg, counts as the %d arg),
        // same cases as the pre-i18n string-substring checks.
        assertTrue(summaries.contains(UiText.StrArgs(R.string.recovery_artifact_missing_segments, listOf("[2, 3]"))))
        assertTrue(summaries.contains(UiText.StrArgs(R.string.recovery_artifact_invalid_segments, listOf("[4]"))))
        assertTrue(summaries.contains(UiText.StrArgs(R.string.recovery_artifact_orphan_segments, listOf("[7]"))))
        assertTrue(summaries.contains(UiText.StrArgs(R.string.recovery_artifact_invalid_orphans, listOf(1))))
        assertTrue(summaries.contains(UiText.StrArgs(R.string.recovery_artifact_duplicate_segments, listOf("[1]"))))
        assertTrue(summaries.contains(UiText.StrArgs(R.string.recovery_artifact_unknown_files, listOf(2))))
        assertTrue(summaries.contains(UiText.StrArgs(R.string.recovery_artifact_malformed_records, listOf(1))))
    }

    @Test
    fun `card body matches kind for every renderable terminator`() {
        // Slice 13C — body copy states cause (per kind), status, and
        // consequence. B3 i18n task 8: the body is now an @StringRes id
        // resolved at the composable; the actual prose ("stay on your
        // device", "permanent", the per-kind cause sentence) lives in
        // strings.xml verbatim. This test pins the id→kind mapping; the
        // copy contents are covered by the resource file itself.
        for ((t, expectedBodyRes) in listOf(
            Terminated.USER_STOPPED to R.string.recovery_body_user_stopped,
            Terminated.KILLED_BY_SYSTEM to R.string.recovery_body_killed_by_system,
            Terminated.KILLED_FORCE_STOP to R.string.recovery_body_force_stopped
        )) {
            val ui = mapAt(
                listOf(view(terminated = t, eligibility = DiscardEligibility.OFFER_DISCARD))
            )
            val card = ui.cards.single()
            assertNotNull("card for $t", card)
            assertEquals("bodyRes for $t", expectedBodyRes, card.bodyRes)
        }
    }

    // ─── Slice 13C destructive-action safety copy (B3 i18n task 8) ──
    // The body prose now lives in strings.xml, so the JVM can't read it
    // off RecoveryCardState. These two guard the *content* contract at
    // the resource layer where it moved: an intentional wording change
    // to the recovery bodies must update these (and pass review). They
    // replace the pre-i18n `body.contains(...)` asserts so the safety
    // wording can never silently vanish. Reviewer + codex (B3 review)
    // called the comment-only "verified once at the resource layer"
    // precedent insufficient for a destructive-action phrase.

    @Test
    fun `every renderable body string promises segments stay until discarded`() {
        for (name in RECOVERY_BODY_RES_NAMES) {
            val value = stringResourceValue(name)
            assertTrue(
                "$name must reassure that recovered segments stay until discard, was: $value",
                value.contains("stay on your device"),
            )
        }
    }

    @Test
    fun `every renderable body string warns the discard action is permanent`() {
        for (name in RECOVERY_BODY_RES_NAMES) {
            val value = stringResourceValue(name)
            assertTrue(
                "$name must mark Discard as permanent, was: $value",
                value.contains("This action is permanent."),
            )
        }
    }

    // ─── Phase 4.3 — merge / keepRaw labels ───────────────────────

    @Test
    fun `mapper populates mergeLabel and keepRawLabel when survivingArtifacts non-empty`() {
        val state = mapAt(
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
        assertEquals(R.string.recovery_merge_label, card.mergeLabelRes)
        assertEquals(R.string.recovery_keep_raw_label, card.keepRawLabelRes)
        assertNull(card.mergeInProgress)
        assertNull(card.mergeFailedReason)
    }

    @Test
    fun `mapper leaves mergeLabel null when no surviving artifacts`() {
        val state = mapAt(
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
        assertNull(card.mergeLabelRes)
        assertNull(card.keepRawLabelRes)
    }

    // ─── Phase 4.3 — MULTI_SEGMENT_KEPT hide rule ─────────────────

    @Test
    fun `MULTI_SEGMENT_KEPT terminated hides the card`() {
        // Phase 4.3 — user chose keep-as-raw-clips; no recovery card
        // should surface. isEligible returns false, map returns Empty.
        val ui = mapAt(
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

    // ─── Task 6: per-reason copy derivation ───────────────────────

    @Test fun `thermal user-stop maps to SafetyStopped with cool-down copy`() {
        val card = mapAt(
            listOf(view(terminated = Terminated.USER_STOPPED, eligibility = DiscardEligibility.OFFER_DISCARD, stopReason = StopReason.THERMAL))
        ).cards.single()
        assertEquals(RecoveryCardKind.SAFETY_STOPPED, card.kind)
        assertEquals(R.string.recovery_title_safety_thermal, card.titleRes)
        assertEquals(R.string.recovery_body_safety_thermal, card.bodyRes)
    }

    @Test fun `low-storage user-stop uses storage copy`() {
        val card = mapAt(
            listOf(view(terminated = Terminated.USER_STOPPED, eligibility = DiscardEligibility.OFFER_DISCARD, stopReason = StopReason.LOW_STORAGE))
        ).cards.single()
        assertEquals(RecoveryCardKind.SAFETY_STOPPED, card.kind)
        assertEquals(R.string.recovery_title_safety_storage, card.titleRes)
    }

    @Test fun `scheduled-window user-stop maps to ScheduledEnd`() {
        val card = mapAt(
            listOf(view(terminated = Terminated.USER_STOPPED, eligibility = DiscardEligibility.OFFER_DISCARD, stopReason = StopReason.SCHEDULE_WINDOW))
        ).cards.single()
        assertEquals(RecoveryCardKind.SCHEDULED_END, card.kind)
        assertEquals(R.string.recovery_title_scheduled, card.titleRes)
    }

    @Test fun `permission-revoked user-stop maps to ErrorStopped`() {
        val card = mapAt(
            listOf(view(terminated = Terminated.USER_STOPPED, eligibility = DiscardEligibility.OFFER_DISCARD, stopReason = StopReason.PERMISSION_REVOKED))
        ).cards.single()
        assertEquals(RecoveryCardKind.ERROR_STOPPED, card.kind)
        assertEquals(R.string.recovery_title_error, card.titleRes)
    }

    @Test fun `manual user-stop keeps existing copy`() {
        val card = mapAt(
            listOf(view(terminated = Terminated.USER_STOPPED, eligibility = DiscardEligibility.OFFER_DISCARD, stopReason = StopReason.USER))
        ).cards.single()
        assertEquals(RecoveryCardKind.USER_STOPPED, card.kind)
        assertEquals(R.string.recovery_title_user_stopped, card.titleRes)
    }

    @Test fun `system kill unchanged - showVendorHelpSlot true`() {
        val card = mapAt(
            listOf(view(terminated = Terminated.KILLED_BY_SYSTEM, eligibility = DiscardEligibility.OFFER_DISCARD, stopReason = StopReason.NONE))
        ).cards.single()
        assertEquals(RecoveryCardKind.KILLED_BY_SYSTEM, card.kind)
        assertTrue(card.showVendorHelpSlot)
    }

    // ─── M3: APPX-G recency — the Clock seam ──────────────────────
    // The mapper mints ONE recency label per card, from the injected clock, at map time.
    // APPX-G: "Recency is never a live region … It is a static label." So: no ticker, no
    // polling, no Flow — the only thing that can ever change the label is a re-map.

    @Test
    fun `card recency is minted from the injected clock, not the wall clock`() {
        val card = mapAt(
            listOf(
                view(
                    terminated = Terminated.USER_STOPPED,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    terminatedAt = FIXED_NOW - 2 * 3_600_000L,
                )
            )
        ).cards.single()

        assertEquals(RelativeTimeKind.HOURS, card.recency.kind)
        assertEquals(2, card.recency.count)
        assertEquals(FIXED_NOW - 2 * 3_600_000L, card.recency.atMillis)
    }

    @Test
    fun `recency anchors on terminatedAt when present`() {
        val card = mapAt(
            listOf(
                view(
                    terminated = Terminated.USER_STOPPED,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    startedAt = FIXED_NOW - 6 * 86_400_000L,   // 6 days ago — must NOT win
                    terminatedAt = FIXED_NOW - 30_000L,        // 30s ago
                )
            )
        ).cards.single()
        assertEquals(RelativeTimeKind.JUST_NOW, card.recency.kind)
    }

    @Test
    fun `recency falls back to startedAt when terminatedAt is null`() {
        val card = mapAt(
            listOf(
                view(
                    terminated = Terminated.KILLED_FORCE_STOP,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    startedAt = FIXED_NOW - 3 * 86_400_000L,
                    terminatedAt = null,
                )
            )
        ).cards.single()
        assertEquals(RelativeTimeKind.DAYS, card.recency.kind)
        assertEquals(3, card.recency.count)
    }

    /**
     * The seam is a *single read*. A clock that counts its own calls proves the mapper neither
     * polls nor re-reads time per card — which is what makes the emitted label internally
     * consistent (two cards mapped together can never disagree about "now").
     */
    @Test
    fun `mapper reads the clock exactly once per map call`() {
        var reads = 0
        val counting = RecoveryClock { reads++; FIXED_NOW }

        RecoveryUiStateMapper.map(
            (1..4).map { i ->
                view(
                    sessionId = "s$i",
                    terminated = Terminated.USER_STOPPED,
                    eligibility = DiscardEligibility.OFFER_DISCARD,
                    terminatedAt = FIXED_NOW - i * 1_000L,
                )
            },
            counting,
        )
        assertEquals(1, reads)
    }

    /** No live updates: an already-mapped card is a frozen value; time moving on cannot touch it. */
    @Test
    fun `an emitted card is immutable as the clock advances`() {
        val views = listOf(
            view(
                terminated = Terminated.USER_STOPPED,
                eligibility = DiscardEligibility.OFFER_DISCARD,
                terminatedAt = FIXED_NOW - 30_000L,
            )
        )
        val card = mapAt(views).cards.single()
        assertEquals(RelativeTimeKind.JUST_NOW, card.recency.kind)

        // Advance the clock a week and re-map: the NEW card ages, the OLD card does not.
        val later = mapAt(views, nowMillis = FIXED_NOW + 7 * 86_400_000L).cards.single()
        assertEquals(RelativeTimeKind.DATE, later.recency.kind)
        assertEquals(RelativeTimeKind.JUST_NOW, card.recency.kind)
    }

    /** Determinism: same views + same clock ⇒ equal states, always. */
    @Test
    fun `mapping is deterministic under a fixed clock`() {
        val views = listOf(
            view(
                terminated = Terminated.KILLED_BY_SYSTEM,
                eligibility = DiscardEligibility.OFFER_DISCARD,
                terminatedAt = FIXED_NOW - 90 * 60_000L,
            )
        )
        assertEquals(mapAt(views), mapAt(views))
    }

    /** An empty eligible set never touches the clock — nothing to label. */
    @Test
    fun `empty input does not read the clock`() {
        var reads = 0
        val counting = RecoveryClock { reads++; FIXED_NOW }
        assertEquals(RecoveryUiState.Empty, RecoveryUiStateMapper.map(emptyList(), counting))
        assertEquals(0, reads)
    }

    // ─── resource-content helpers (B3 i18n task 8) ────────────────

    private fun stringResourceValue(name: String): String {
        // Gradle runs unit tests with the working dir at the module root
        // (`app/`); tolerate the repo root too so the test survives a
        // different launcher. Read the raw XML and undo the escapes Android
        // requires (`\'`, `&amp;`, `&lt;`, `&gt;`) so asserts see plain copy.
        val candidates = listOf(
            java.io.File("src/main/res/values/strings.xml"),
            java.io.File("app/src/main/res/values/strings.xml"),
        )
        val xml = candidates.firstOrNull { it.exists() }
            ?: error("strings.xml not found in ${candidates.map { it.absolutePath }}")
        val match = Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(xml.readText())
            ?: error("string resource '$name' not found in ${xml.absolutePath}")
        return match.groupValues[1]
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    private companion object {
        /** 2026-07-09T16:12:00Z. Arbitrary but fixed — the mapper must never read the wall clock. */
        const val FIXED_NOW = 1_783_613_520_000L

        val RECOVERY_BODY_RES_NAMES = listOf(
            "recovery_body_user_stopped",
            "recovery_body_killed_by_system",
            "recovery_body_force_stopped",
            "recovery_body_safety_thermal",
            "recovery_body_safety_storage",
            "recovery_body_scheduled",
            "recovery_body_error",
        )
    }
}
