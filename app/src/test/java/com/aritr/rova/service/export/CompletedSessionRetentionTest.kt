package com.aritr.rova.service.export

import com.aritr.rova.data.AudioMode
import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SegmentRecord
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.recovery.Anomaly
import com.aritr.rova.service.recovery.DiscardEligibility
import com.aritr.rova.service.recovery.RecoveryScanner
import com.aritr.rova.service.recovery.TerminalAction
import com.aritr.rova.utils.MediaFileInspection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Completed-session **retention contract** (spec
 * `docs/superpowers/specs/2026-06-03-completed-session-retention-contract-design.md`;
 * ADR-0005 §"Completed-session retention"). Closes smoke-test finding #10 as
 * working-as-designed.
 *
 * Invariant: a successfully-COMPLETED session directory IS the manifest-backed
 * Library/History index and is **retained** by the cold-launch recovery+cleanup
 * pass. It is removed only by an explicit user delete
 * ([SessionStore.discardSession], via `HistoryDeleter`) or the opt-in
 * keep-latest-N `RecordingRetentionCleaner` — never by the automatic cleanup
 * pass.
 *
 * Mechanism this suite pins (so a future refactor cannot silently break it):
 * after `performMerge` deletes the on-disk `segment_*.mp4` files on export
 * success, the manifest's segment **records** survive. `RecoveryScanner` then
 * sees manifest keys with no disk file → [Anomaly.MissingSegment] → eligibility
 * [DiscardEligibility.OFFER_DISCARD] (not `AUTO_DISCARD_ELIGIBLE`) →
 * [ExportCleanupPredicate] gate 1 blocks deletion. Two refactors would break
 * this and start auto-deleting completed recordings: clearing `manifest.segments`
 * on COMPLETED, or exempting COMPLETED sessions from missing-segment anomalies.
 * Either one fails the primary test below.
 */
class CompletedSessionRetentionTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var store: SessionStore

    @Before
    fun setUp() {
        rootDir = tmp.newFolder("videos")
        store = SessionStore(rootDir)
    }

    @After
    fun tearDown() {
        store.close()
    }

    private fun newScanner(): RecoveryScanner = RecoveryScanner(
        sessionStore = store,
        now = { NOW },
        inspect = { MediaFileInspection(isValid = true, durationMs = 1_000L) },
        liveSessionId = { null },
        sha1 = { "deadbeef" }
    )

    /**
     * Seed an on-disk session dir representing a finished recording:
     * [segmentRecordCount] segment records persisted in the manifest, and
     * [diskSegmentCount] matching `segment_*.mp4` files actually present on disk.
     * `Terminated.COMPLETED` with the canonical `StopReason.NONE` (per ADR-0006
     * §"Migration table" — COMPLETED writers pass NONE; `performMerge` and
     * `ExportRecoveryRunner` both do).
     *
     * The two counts model the two retention paths:
     * - `diskSegmentCount == 0` — post-merge-success: segment files deleted,
     *   records survive → `MissingSegmentAnomaly` → OFFER_DISCARD.
     * - `diskSegmentCount == segmentRecordCount` — a segment delete FAILED
     *   (best-effort cleanup swallows the exception): files survive →
     *   `anySurvivors` → OFFER_DISCARD, no MissingSegment.
     */
    private fun seedCompletedSession(
        sessionId: String,
        segmentRecordCount: Int,
        diskSegmentCount: Int = 0
    ) {
        val dir = File(rootDir, sessionId).also { it.mkdirs() }
        val records = (1..segmentRecordCount).map { i ->
            SegmentRecord(
                filename = "segment_%04d.mp4".format(i),
                durationMs = 1_000L,
                sizeBytes = 1L,
                sha1 = "sha-$i"
            )
        }
        repeat(diskSegmentCount) { i ->
            File(dir, "segment_%04d.mp4".format(i + 1)).writeBytes(byteArrayOf(0x00))
        }
        val manifest = SessionManifest(
            sessionId = sessionId,
            startedAt = STARTED_AT,
            config = SessionConfig(
                durationSeconds = 5,
                intervalMinutes = 1,
                resolution = "720p",
                loopCount = 0
            ),
            segments = records,
            exportTier = ExportTier.TIER1_API29_PLUS,
            exportState = ExportState.FINALIZED,
            terminated = Terminated.COMPLETED,
            terminatedAt = 1L,
            stopRequested = false,
            stopReason = StopReason.NONE,
            audioMode = AudioMode.VIDEO_ONLY
        )
        File(dir, "manifest.json").writeText(manifest.toJson().toString())
    }

    private val cleanSweep = OrphanSweepResult.Swept(0, 0, 0, 0)

    private fun report() = ExportRecoveryReport(
        referencedPendingUris = emptySet(),
        perSession = emptyMap(),
        lateTerminals = emptyMap(),
        sweep = cleanSweep
    )

    // ── Primary: a real completed session is retained by cleanup ────────────

    @Test
    fun `completed session with deleted segments is retained, not auto-discarded`() = runBlocking {
        val sid = "completed-retained"
        seedCompletedSession(sid, segmentRecordCount = 2)

        val classification = newScanner().classify(sid, NOW)

        // COMPLETED is already terminal — scanner writes no new terminal value.
        assertEquals(TerminalAction.ALREADY_TERMINAL, classification.terminalAction)
        // Deleted-on-disk manifest segments surface as MissingSegment.
        assertTrue(
            "expected MissingSegment anomaly; got ${classification.anomalies}",
            classification.anomalies.any { it is Anomaly.MissingSegment }
        )
        // The load-bearing fact: NOT auto-discard-eligible.
        assertEquals(DiscardEligibility.OFFER_DISCARD, classification.eligibility)

        // Cleanup gate 1 therefore blocks deletion.
        val manifest = store.loadManifest(sid)!!
        assertFalse(
            "completed session must not pass the cleanup delete gate",
            ExportCleanupPredicate.shouldDelete(
                classification = classification,
                manifest = manifest,
                recoveryResult = null,
                sweepResult = cleanSweep
            )
        )

        // And the orchestrated pass leaves the dir on disk.
        val deleted = ExportCleanupPredicate.runCleanupPass(
            store,
            mapOf(sid to classification),
            report()
        )
        assertTrue("cleanup must delete nothing; got $deleted", deleted.isEmpty())
        assertTrue("session dir must remain", store.sessionDir(sid).exists())
    }

    // ── Second retention path: a failed segment delete leaves files behind ──

    @Test
    fun `completed session whose segment delete failed is retained via surviving files`() = runBlocking {
        // performMerge's post-success cleanup is best-effort (it swallows
        // delete exceptions). If a segment file survives, there is NO
        // MissingSegment anomaly, but `anySurvivors` still forces OFFER_DISCARD
        // — so retention does not depend solely on the MissingSegment path.
        val sid = "completed-delete-failed"
        seedCompletedSession(sid, segmentRecordCount = 2, diskSegmentCount = 2)

        val classification = newScanner().classify(sid, NOW)

        assertEquals(TerminalAction.ALREADY_TERMINAL, classification.terminalAction)
        assertTrue(
            "no MissingSegment expected when files survive; got ${classification.anomalies}",
            classification.anomalies.none { it is Anomaly.MissingSegment }
        )
        assertEquals(DiscardEligibility.OFFER_DISCARD, classification.eligibility)

        val manifest = store.loadManifest(sid)!!
        assertFalse(
            ExportCleanupPredicate.shouldDelete(classification, manifest, null, cleanSweep)
        )
        assertTrue(store.sessionDir(sid).exists())
    }

    // ── Zero-segment COMPLETED: retained, not auto-deleted ──────────────────

    @Test
    fun `completed session with zero segment records is still retained, not auto-discard-eligible`() =
        runBlocking {
            // A COMPLETED manifest with NO segment records and no disk files has
            // no missing keys, no survivors, no anomalies — so the only thing
            // keeping it out of AUTO_DISCARD_ELIGIBLE is the COMPLETED terminal
            // itself. `RecoveryScanner` exempts T == COMPLETED from auto-discard
            // unconditionally (ADR-0005 §"Discard Eligibility"): a finished
            // recording is never auto-deleted, even in this degenerate shape.
            //
            // Reachable over abnormal persisted data — the late-terminal recovery
            // writer `ExportRecoveryRunner` writes COMPLETED for any FINALIZED
            // manifest with a valid artifact without a segment-count guard.
            val sid = "completed-empty-retained"
            seedCompletedSession(sid, segmentRecordCount = 0)

            val classification = newScanner().classify(sid, NOW)

            assertEquals(TerminalAction.ALREADY_TERMINAL, classification.terminalAction)
            assertTrue("no anomalies expected; got ${classification.anomalies}", classification.anomalies.isEmpty())
            assertEquals(DiscardEligibility.OFFER_DISCARD, classification.eligibility)

            // Cleanup gate 1 therefore blocks deletion.
            val manifest = store.loadManifest(sid)!!
            assertFalse(
                "zero-segment COMPLETED must not pass the cleanup delete gate",
                ExportCleanupPredicate.shouldDelete(classification, manifest, null, cleanSweep)
            )
        }

    // ── Manual delete still removes a retained session ──────────────────────

    @Test
    fun `discardSession removes a retained completed session`() {
        val sid = "completed-manual-delete"
        seedCompletedSession(sid, segmentRecordCount = 2)
        assertTrue(store.sessionDir(sid).exists())

        store.discardSession(sid)

        assertFalse(
            "manual discard (HistoryDeleter path) must remove the dir",
            store.sessionDir(sid).exists()
        )
    }

    companion object {
        private const val NOW = 1_000_000_000L
        private const val STARTED_AT = NOW - 60_000L  // outside RecoveryScanner age filter
    }
}
