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
 */
class RecoveryViewSourceTest {

    private fun manifest(sessionId: String, terminated: Terminated?) = SessionManifest(
        sessionId = sessionId,
        startedAt = 0L,
        config = SessionConfig(30, 5, "FHD", 4),
        segments = emptyList(),
        exportTier = ExportTier.TIER1_API29_PLUS,
        terminated = terminated
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
    }

    @Test
    fun `manifest present routes through the 2_0_5 mapper`() {
        val sid = "s-user"
        val ui = RecoveryViewSource.buildUiState(
            report = report(classification(sid))
        ) { id ->
            if (id == sid) manifest(sid, Terminated.USER_STOPPED) else null
        }
        assertEquals(1, ui.cards.size)
        assertEquals(sid, ui.cards.single().sessionId)
        assertEquals(RecoveryCardKind.USER_STOPPED, ui.cards.single().kind)
    }

    @Test
    fun `mixed manifests - missing dropped while present render`() {
        val present = "s-present"
        val missing = "s-missing"
        val ui = RecoveryViewSource.buildUiState(
            report = report(classification(present), classification(missing))
        ) { id ->
            if (id == present) manifest(present, Terminated.KILLED_BY_SYSTEM) else null
        }
        assertEquals(listOf(present), ui.cards.map { it.sessionId })
        assertEquals(RecoveryCardKind.KILLED_BY_SYSTEM, ui.cards.single().kind)
        assertTrue(ui.cards.single().showVendorHelpSlot)
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
        // a recovery card — the 2.0.5 mapper enforces this and the
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
    fun `iteration order follows classifications map order`() {
        val ui = RecoveryViewSource.buildUiState(
            report = report(
                classification("first"),
                classification("second"),
                classification("third")
            )
        ) { id ->
            when (id) {
                "first" -> manifest(id, Terminated.USER_STOPPED)
                "second" -> manifest(id, Terminated.KILLED_BY_SYSTEM)
                "third" -> manifest(id, Terminated.KILLED_FORCE_STOP)
                else -> null
            }
        }
        assertEquals(listOf("first", "second", "third"), ui.cards.map { it.sessionId })
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
        // The 2.0.5 mapper hides on terminated=null and we must
        // preserve that even via the adapter.
        val sid = "s-race"
        val ui = RecoveryViewSource.buildUiState(
            report = report(classification(sid))
        ) { id ->
            if (id == sid) manifest(sid, terminated = null) else null
        }
        assertTrue(ui.cards.isEmpty())
    }

    @Test
    fun `regression - vendor slot only true for KILLED_BY_SYSTEM`() {
        val ids = listOf(
            Terminated.USER_STOPPED to false,
            Terminated.KILLED_BY_SYSTEM to true,
            Terminated.KILLED_FORCE_STOP to false
        )
        for ((t, expectVendor) in ids) {
            val sid = "s-${t.name}"
            val ui = RecoveryViewSource.buildUiState(
                report = report(classification(sid))
            ) { id -> if (id == sid) manifest(sid, t) else null }
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
