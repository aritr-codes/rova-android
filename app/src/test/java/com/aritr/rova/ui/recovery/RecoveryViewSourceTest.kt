package com.aritr.rova.ui.recovery

import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.recovery.DiscardEligibility
import com.aritr.rova.service.recovery.RecoveryReport
import com.aritr.rova.service.recovery.SessionClassification
import com.aritr.rova.service.recovery.TerminalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 Slice 2.1a — pure tests for the RecoveryReport →
 * RecoveryUiState adapter. No Compose, no Android, no SessionStore —
 * the `loadManifest` lookup is a function parameter so the adapter is
 * fully unit-testable on the JVM.
 *
 * Internal beta correction: the adapter now takes an optional
 * `dismissedIds` set so the [RecoveryViewModel] can hide a discarded
 * card immediately without waiting for the next disk scan.
 */
class RecoveryViewSourceTest {

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
        eligibility: DiscardEligibility = DiscardEligibility.OFFER_DISCARD
    ) = SessionClassification(
        sessionId = sessionId,
        terminalAction = TerminalAction.ALREADY_TERMINAL,
        eligibility = eligibility,
        anomalies = emptyList(),
        appendedSegmentFilenames = emptyList()
    )

    private fun report(vararg c: SessionClassification): RecoveryReport =
        RecoveryReport(
            classifications = c.associateBy { it.sessionId },
            scanStartMillis = 1L,
            scanCompletedMillis = 2L
        )

    // ─── buildUiState ──────────────────────────────────────────────

    @Test
    fun `null report yields RecoveryUiState Empty`() {
        val ui = RecoveryViewSource.buildUiState(report = null) { _ -> null }
        assertEquals(RecoveryUiState.Empty, ui)
    }

    @Test
    fun `empty classifications yields RecoveryUiState Empty`() {
        val ui = RecoveryViewSource.buildUiState(
            report = RecoveryReport(
                classifications = emptyMap(),
                scanStartMillis = 0L,
                scanCompletedMillis = 0L
            )
        ) { _ -> null }
        assertEquals(RecoveryUiState.Empty, ui)
    }

    @Test
    fun `manifest miss drops the session silently`() {
        val ui = RecoveryViewSource.buildUiState(
            report = report(classification("missing"))
        ) { _ -> null }
        assertTrue(ui.cards.isEmpty())
        assertEquals(0, ui.hiddenCount)
    }

    @Test
    fun `manifest present routes through the mapper`() {
        val sid = "s-user"
        val ui = RecoveryViewSource.buildUiState(
            report = report(classification(sid))
        ) { id ->
            if (id == sid) manifest(sid, Terminated.USER_STOPPED, terminatedAt = 100L) else null
        }
        assertEquals(1, ui.cards.size)
        assertEquals(sid, ui.cards.single().sessionId)
        assertEquals(RecoveryCardKind.USER_STOPPED, ui.cards.single().kind)
        assertEquals(0, ui.hiddenCount)
    }

    @Test
    fun `mixed manifests - newest renders, missing dropped`() {
        val present = "s-present"
        val missing = "s-missing"
        val ui = RecoveryViewSource.buildUiState(
            report = report(classification(present), classification(missing))
        ) { id ->
            if (id == present) manifest(present, Terminated.KILLED_BY_SYSTEM, terminatedAt = 100L) else null
        }
        assertEquals(listOf(present), ui.cards.map { it.sessionId })
        assertEquals(RecoveryCardKind.KILLED_BY_SYSTEM, ui.cards.single().kind)
        assertTrue(ui.cards.single().showVendorHelpSlot)
        assertEquals(0, ui.hiddenCount)
    }

    @Test
    fun `BLOCKED eligibility hides even when manifest present`() {
        val sid = "s-blocked"
        val ui = RecoveryViewSource.buildUiState(
            report = report(classification(sid, DiscardEligibility.BLOCKED))
        ) { id ->
            if (id == sid) manifest(sid, Terminated.USER_STOPPED) else null
        }
        assertTrue(ui.cards.isEmpty())
    }

    @Test
    fun `AUTO_DISCARD_ELIGIBLE hides even when manifest present`() {
        val sid = "s-auto"
        val ui = RecoveryViewSource.buildUiState(
            report = report(classification(sid, DiscardEligibility.AUTO_DISCARD_ELIGIBLE))
        ) { id ->
            if (id == sid) manifest(sid, Terminated.KILLED_FORCE_STOP) else null
        }
        assertTrue(ui.cards.isEmpty())
    }

    @Test
    fun `COMPLETED terminator hides even when classification offers discard`() {
        // Stale OFFER_DISCARD on a finalized session must never raise
        // a recovery card — the mapper enforces this and the
        // adapter must propagate it unchanged.
        val sid = "s-done"
        val ui = RecoveryViewSource.buildUiState(
            report = report(classification(sid))
        ) { id ->
            if (id == sid) manifest(sid, Terminated.COMPLETED) else null
        }
        assertTrue(ui.cards.isEmpty())
    }

    @Test
    fun `newest by terminatedAt wins regardless of classification map order`() {
        val ui = RecoveryViewSource.buildUiState(
            report = report(
                classification("first"),
                classification("second"),
                classification("third")
            )
        ) { id ->
            when (id) {
                "first" -> manifest(id, Terminated.USER_STOPPED, terminatedAt = 100L)
                "second" -> manifest(id, Terminated.KILLED_BY_SYSTEM, terminatedAt = 300L)
                "third" -> manifest(id, Terminated.KILLED_FORCE_STOP, terminatedAt = 200L)
                else -> null
            }
        }
        assertEquals(listOf("second"), ui.cards.map { it.sessionId })
        assertEquals(2, ui.hiddenCount)
    }

    // ─── dismissedIds ─────────────────────────────────────────────

    @Test
    fun `dismissedIds excludes matching sessions before manifest lookup`() {
        var loadCount = 0
        val ui = RecoveryViewSource.buildUiState(
            report = report(classification("a"), classification("b")),
            dismissedIds = setOf("a")
        ) { id ->
            loadCount++
            when (id) {
                "b" -> manifest(id, Terminated.USER_STOPPED, terminatedAt = 100L)
                else -> null
            }
        }
        assertEquals(listOf("b"), ui.cards.map { it.sessionId })
        assertEquals(0, ui.hiddenCount)
        // Only "b" was looked up; "a" was filtered before manifest IO.
        assertEquals(1, loadCount)
    }

    @Test
    fun `dismissedIds filtering can collapse to Empty`() {
        val ui = RecoveryViewSource.buildUiState(
            report = report(classification("a")),
            dismissedIds = setOf("a")
        ) { _ -> manifest("a", Terminated.USER_STOPPED, terminatedAt = 100L) }
        assertTrue(ui.cards.isEmpty())
        assertEquals(0, ui.hiddenCount)
    }

    @Test
    fun `dismissedIds reduces hiddenCount when newest is hidden`() {
        val ui = RecoveryViewSource.buildUiState(
            report = report(
                classification("newest"),
                classification("middle"),
                classification("oldest")
            ),
            dismissedIds = setOf("newest")
        ) { id ->
            when (id) {
                "middle" -> manifest(id, Terminated.USER_STOPPED, terminatedAt = 200L)
                "oldest" -> manifest(id, Terminated.USER_STOPPED, terminatedAt = 100L)
                else -> null
            }
        }
        // newest is dismissed; middle is the new visible card.
        assertEquals(listOf("middle"), ui.cards.map { it.sessionId })
        assertEquals(1, ui.hiddenCount)
    }

    // ─── buildViews ────────────────────────────────────────────────

    @Test
    fun `buildViews exposes raw view list for tests and filtered surfaces`() {
        val sid = "s"
        val views = RecoveryViewSource.buildViews(
            classifications = listOf(classification(sid)),
            loadManifest = { id -> if (id == sid) manifest(sid, Terminated.USER_STOPPED) else null }
        )
        assertEquals(1, views.size)
        assertEquals(sid, views.single().manifest.sessionId)
        assertEquals(sid, views.single().classification.sessionId)
    }

    @Test
    fun `buildViews drops missing manifests without throwing`() {
        val views = RecoveryViewSource.buildViews(
            classifications = listOf(
                classification("present"),
                classification("missing")
            ),
            loadManifest = { id -> if (id == "present") manifest(id, Terminated.USER_STOPPED) else null }
        )
        assertEquals(1, views.size)
        assertEquals("present", views.single().manifest.sessionId)
    }

    @Test
    fun `buildViews on empty input returns empty list`() {
        val views = RecoveryViewSource.buildViews(
            classifications = emptyList(),
            loadManifest = { _ -> null }
        )
        assertTrue(views.isEmpty())
    }

    // ─── regression guard ──────────────────────────────────────────

    @Test
    fun `regression - manifest with terminated null but offer-discard hides`() {
        // Phase 1.5 BLOCKED branch — but an inconsistent state where
        // classification says OFFER_DISCARD while the manifest says
        // terminated=null can happen across a scan/load race.
        // The mapper hides on terminated=null and we must
        // preserve that even via the adapter.
        val sid = "s-race"
        val ui = RecoveryViewSource.buildUiState(
            report = report(classification(sid))
        ) { id ->
            if (id == sid) manifest(sid, terminated = null) else null
        }
        assertTrue(ui.cards.isEmpty())
    }

    // ─── eligibleSessionCount ─────────────────────────────────────

    @Test
    fun `eligibleSessionCount returns 0 for null report`() {
        val n = RecoveryViewSource.eligibleSessionCount(report = null) { _ -> null }
        assertEquals(0, n)
    }

    @Test
    fun `eligibleSessionCount returns 0 when classifications are empty`() {
        val n = RecoveryViewSource.eligibleSessionCount(
            report = RecoveryReport(
                classifications = emptyMap(),
                scanStartMillis = 0L,
                scanCompletedMillis = 0L
            )
        ) { _ -> null }
        assertEquals(0, n)
    }

    @Test
    fun `eligibleSessionCount counts only OFFER_DISCARD with eligible terminator`() {
        val n = RecoveryViewSource.eligibleSessionCount(
            report = report(
                classification("user", DiscardEligibility.OFFER_DISCARD),
                classification("blocked", DiscardEligibility.BLOCKED),
                classification("auto", DiscardEligibility.AUTO_DISCARD_ELIGIBLE),
                classification("done", DiscardEligibility.OFFER_DISCARD)
            )
        ) { id ->
            when (id) {
                "user" -> manifest(id, Terminated.USER_STOPPED, terminatedAt = 100L)
                "blocked" -> manifest(id, Terminated.USER_STOPPED, terminatedAt = 100L)
                "auto" -> manifest(id, Terminated.KILLED_FORCE_STOP, terminatedAt = 100L)
                "done" -> manifest(id, Terminated.COMPLETED, terminatedAt = 100L)
                else -> null
            }
        }
        // Only "user" survives the same filter History applies.
        assertEquals(1, n)
    }

    @Test
    fun `eligibleSessionCount equals visible plus hidden count`() {
        val n = RecoveryViewSource.eligibleSessionCount(
            report = report(
                classification("a"),
                classification("b"),
                classification("c")
            )
        ) { id ->
            when (id) {
                "a" -> manifest(id, Terminated.USER_STOPPED, terminatedAt = 100L)
                "b" -> manifest(id, Terminated.KILLED_BY_SYSTEM, terminatedAt = 200L)
                "c" -> manifest(id, Terminated.KILLED_FORCE_STOP, terminatedAt = 300L)
                else -> null
            }
        }
        // Mapper would emit 1 visible + 2 hidden = 3.
        assertEquals(3, n)
    }

    @Test
    fun `eligibleSessionCount honors dismissedIds`() {
        val n = RecoveryViewSource.eligibleSessionCount(
            report = report(classification("a"), classification("b")),
            dismissedIds = setOf("a")
        ) { id ->
            when (id) {
                "a" -> manifest(id, Terminated.USER_STOPPED, terminatedAt = 100L)
                "b" -> manifest(id, Terminated.USER_STOPPED, terminatedAt = 200L)
                else -> null
            }
        }
        assertEquals(1, n)
    }

    @Test
    fun `eligibleSessionCount drops manifest miss`() {
        val n = RecoveryViewSource.eligibleSessionCount(
            report = report(classification("missing"))
        ) { _ -> null }
        assertEquals(0, n)
    }

    @Test
    fun `regression - vendor slot only true for KILLED_BY_SYSTEM visible card`() {
        val ids = listOf(
            Terminated.USER_STOPPED to false,
            Terminated.KILLED_BY_SYSTEM to true,
            Terminated.KILLED_FORCE_STOP to false
        )
        for ((t, expectVendor) in ids) {
            val sid = "s-${t.name}"
            val ui = RecoveryViewSource.buildUiState(
                report = report(classification(sid))
            ) { id -> if (id == sid) manifest(sid, t, terminatedAt = 100L) else null }
            val card = ui.cards.single()
            assertEquals("kind for $t", t.name, card.kind.name)
            if (expectVendor) {
                assertTrue("vendor slot for $t", card.showVendorHelpSlot)
            } else {
                assertFalse("vendor slot for $t", card.showVendorHelpSlot)
            }
        }
    }
}
