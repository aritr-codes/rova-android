package com.aritr.rova.service.export

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import com.aritr.rova.service.recovery.Anomaly
import com.aritr.rova.service.recovery.DiscardEligibility
import com.aritr.rova.service.recovery.SessionClassification
import com.aritr.rova.service.recovery.TerminalAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Phase 1.7 commit-6 — [ExportCleanupPredicate] regression suite.
 *
 * Covers each of the four cleanup gates individually plus the
 * `runCleanupPass` orchestration (per-session iteration, sweep-failed
 * short-circuit, missing manifest skip).
 */
class ExportCleanupPredicateTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var store: SessionStore

    @Before
    fun setUp() {
        store = SessionStore(tmp.newFolder("videos"))
    }

    @After
    fun tearDown() {
        store.close()
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private fun classification(
        sessionId: String,
        eligibility: DiscardEligibility = DiscardEligibility.AUTO_DISCARD_ELIGIBLE,
        anomalies: List<Anomaly> = emptyList()
    ) = SessionClassification(
        sessionId = sessionId,
        terminalAction = TerminalAction.WROTE_KILLED_FORCE_STOP,
        eligibility = eligibility,
        anomalies = anomalies,
        appendedSegmentFilenames = emptyList()
    )

    private fun manifest(
        sessionId: String = "s",
        privateTempPath: String? = null,
        exportState: ExportState = ExportState.FAILED
    ) = SessionManifest(
        sessionId = sessionId,
        startedAt = 0L,
        config = SessionConfig(30, 5, "FHD", 4),
        segments = emptyList(),
        exportTier = ExportTier.TIER1_API29_PLUS,
        privateTempPath = privateTempPath,
        exportState = exportState
    )

    private val cleanSweep = OrphanSweepResult.Swept(0, 0, 0, 0)

    // ─── Gate 1 — AUTO_DISCARD_ELIGIBLE ─────────────────────────────

    @Test
    fun `gate 1 - OFFER_DISCARD blocks delete`() {
        val ok = ExportCleanupPredicate.shouldDelete(
            classification = classification("s", DiscardEligibility.OFFER_DISCARD),
            manifest = manifest(),
            recoveryResult = null,
            sweepResult = cleanSweep
        )
        assertFalse(ok)
    }

    @Test
    fun `gate 1 - BLOCKED blocks delete`() {
        val ok = ExportCleanupPredicate.shouldDelete(
            classification = classification("s", DiscardEligibility.BLOCKED),
            manifest = manifest(),
            recoveryResult = null,
            sweepResult = cleanSweep
        )
        assertFalse(ok)
    }

    // ─── Gate 2 — privateTempPath == null ───────────────────────────

    @Test
    fun `gate 2 - non-null privateTempPath blocks delete - deferred-scan retry retention`() {
        val ok = ExportCleanupPredicate.shouldDelete(
            classification = classification("s"),
            manifest = manifest(privateTempPath = "/tmp/private/x.mp4"),
            recoveryResult = null,
            sweepResult = cleanSweep
        )
        assertFalse(ok)
    }

    // ─── Gate 3 — per-session recovery terminal-clean ──────────────

    @Test
    fun `gate 3 - RetryableFailure blocks delete`() {
        val r = RecoveryResult.RetryableFailure("x", IOException("seam threw"))
        val ok = ExportCleanupPredicate.shouldDelete(
            classification = classification("s"),
            manifest = manifest(),
            recoveryResult = r,
            sweepResult = cleanSweep
        )
        assertFalse(ok)
    }

    @Test
    fun `gate 3 - ManifestWriteFailed blocks delete`() {
        val r = RecoveryResult.ManifestWriteFailed(IOException("retry exhausted"))
        val ok = ExportCleanupPredicate.shouldDelete(
            classification = classification("s"),
            manifest = manifest(),
            recoveryResult = r,
            sweepResult = cleanSweep
        )
        assertFalse(ok)
    }

    @Test
    fun `gate 3 - Resumed passes`() {
        val r = RecoveryResult.Resumed(ExportResult.Success(true, false))
        val ok = ExportCleanupPredicate.shouldDelete(
            classification = classification("s"),
            manifest = manifest(),
            recoveryResult = r,
            sweepResult = cleanSweep
        )
        assertTrue(ok)
    }

    @Test
    fun `gate 3 - Abandoned passes`() {
        val ok = ExportCleanupPredicate.shouldDelete(
            classification = classification("s"),
            manifest = manifest(),
            recoveryResult = RecoveryResult.Abandoned,
            sweepResult = cleanSweep
        )
        assertTrue(ok)
    }

    @Test
    fun `gate 3 - UnknownSession passes`() {
        val ok = ExportCleanupPredicate.shouldDelete(
            classification = classification("s"),
            manifest = manifest(),
            recoveryResult = RecoveryResult.UnknownSession("s"),
            sweepResult = cleanSweep
        )
        assertTrue(ok)
    }

    @Test
    fun `gate 3 - null recoveryResult passes - session was not dispatched`() {
        val ok = ExportCleanupPredicate.shouldDelete(
            classification = classification("s"),
            manifest = manifest(),
            recoveryResult = null,
            sweepResult = cleanSweep
        )
        assertTrue(ok)
    }

    // ─── Gate 4 — sweep clean ───────────────────────────────────────

    @Test
    fun `gate 4 - QueryFailed blocks delete`() {
        val ok = ExportCleanupPredicate.shouldDelete(
            classification = classification("s"),
            manifest = manifest(),
            recoveryResult = null,
            sweepResult = OrphanSweepResult.QueryFailed(IOException("listing threw"))
        )
        assertFalse(ok)
    }

    @Test
    fun `gate 4 - Swept passes regardless of counters`() {
        val ok = ExportCleanupPredicate.shouldDelete(
            classification = classification("s"),
            manifest = manifest(),
            recoveryResult = null,
            sweepResult = OrphanSweepResult.Swept(
                deleted = 5,
                retainedReferenced = 2,
                retainedOtherPackage = 1,
                deleteFailures = 1
            )
        )
        assertTrue(ok)
    }

    // ─── All gates pass ─────────────────────────────────────────────

    @Test
    fun `all four gates pass - delete proceeds`() {
        val ok = ExportCleanupPredicate.shouldDelete(
            classification = classification("s", DiscardEligibility.AUTO_DISCARD_ELIGIBLE),
            manifest = manifest(privateTempPath = null, exportState = ExportState.FAILED),
            recoveryResult = RecoveryResult.Abandoned,
            sweepResult = cleanSweep
        )
        assertTrue(ok)
    }

    // ─── runCleanupPass orchestration ───────────────────────────────

    @Test
    fun `runCleanupPass discards passing sessions and skips others`() {
        val sidPass = makeOnDiskSession()
        val sidBlock = makeOnDiskSession()
        val classifications = mapOf(
            sidPass to classification(sidPass, DiscardEligibility.AUTO_DISCARD_ELIGIBLE),
            sidBlock to classification(sidBlock, DiscardEligibility.OFFER_DISCARD)
        )
        val report = ExportRecoveryReport(
            referencedPendingUris = emptySet(),
            perSession = emptyMap(),
            lateTerminals = emptyMap(),
            sweep = cleanSweep
        )

        val deleted = ExportCleanupPredicate.runCleanupPass(store, classifications, report)

        assertEquals(listOf(sidPass), deleted)
        assertFalse("passing session dir must be deleted", store.sessionDir(sidPass).exists())
        assertTrue("blocked session dir must remain", store.sessionDir(sidBlock).exists())
    }

    @Test
    fun `runCleanupPass short-circuits when sweep is QueryFailed - no delete`() {
        val sid = makeOnDiskSession()
        val classifications = mapOf(
            sid to classification(sid, DiscardEligibility.AUTO_DISCARD_ELIGIBLE)
        )
        val report = ExportRecoveryReport(
            referencedPendingUris = emptySet(),
            perSession = emptyMap(),
            lateTerminals = emptyMap(),
            sweep = OrphanSweepResult.QueryFailed(IOException("listing threw"))
        )

        val deleted = ExportCleanupPredicate.runCleanupPass(store, classifications, report)

        assertTrue("QueryFailed must skip ALL physical cleanup", deleted.isEmpty())
        assertTrue("session dir must remain on QueryFailed", store.sessionDir(sid).exists())
    }

    @Test
    fun `runCleanupPass skips classifications whose manifest is missing`() {
        val sidPresent = makeOnDiskSession()
        val sidGone = "20260101_010101_dead0001"  // never created
        val classifications = mapOf(
            sidPresent to classification(sidPresent),
            sidGone to classification(sidGone)
        )
        val report = ExportRecoveryReport(
            referencedPendingUris = emptySet(),
            perSession = emptyMap(),
            lateTerminals = emptyMap(),
            sweep = cleanSweep
        )

        val deleted = ExportCleanupPredicate.runCleanupPass(store, classifications, report)

        assertEquals(listOf(sidPresent), deleted)
    }

    @Test
    fun `runCleanupPass respects per-session recovery RetryableFailure - blocked despite eligible`() {
        val sid = makeOnDiskSession()
        val classifications = mapOf(sid to classification(sid))
        val report = ExportRecoveryReport(
            referencedPendingUris = emptySet(),
            perSession = mapOf(
                sid to RecoveryResult.RetryableFailure("seam", IOException("x"))
            ),
            lateTerminals = emptyMap(),
            sweep = cleanSweep
        )

        val deleted = ExportCleanupPredicate.runCleanupPass(store, classifications, report)

        assertTrue(deleted.isEmpty())
        assertTrue(store.sessionDir(sid).exists())
    }

    @Test
    fun `runCleanupPass respects manifests post-recovery state - cleared privateTempPath unblocks delete`() {
        // Session was MUXING with privateTempPath set; recovery cleared
        // it via setExportFailed. Cleanup reads the post-recovery state.
        val sid = makeOnDiskSession(privateTempPath = "/tmp/old/private.mp4")
        // Simulate recovery's setExportFailed clearing privateTempPath.
        val current = store.loadManifest(sid)!!
        File(store.sessionDir(sid), "manifest.json")
            .writeText(current.copy(privateTempPath = null).toJson().toString(2))

        val classifications = mapOf(sid to classification(sid))
        val report = ExportRecoveryReport(
            referencedPendingUris = emptySet(),
            perSession = mapOf(sid to RecoveryResult.Abandoned),
            lateTerminals = emptyMap(),
            sweep = cleanSweep
        )

        val deleted = ExportCleanupPredicate.runCleanupPass(store, classifications, report)
        assertEquals(listOf(sid), deleted)
    }

    private fun makeOnDiskSession(privateTempPath: String? = null): String {
        val created = store.createSession(
            config = SessionConfig(30, 5, "FHD", 4)
        )
        if (privateTempPath != null) {
            File(store.sessionDir(created.sessionId), "manifest.json")
                .writeText(created.copy(privateTempPath = privateTempPath).toJson().toString(2))
        }
        // Ensure dir exists (createSession creates it but be explicit).
        assertNotNull(store.loadManifest(created.sessionId))
        return created.sessionId
    }
}
