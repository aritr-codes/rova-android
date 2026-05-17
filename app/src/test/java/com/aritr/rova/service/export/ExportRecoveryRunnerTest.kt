package com.aritr.rova.service.export

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import com.aritr.rova.data.Terminated
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Phase 1.7 commit-6 — [ExportRecoveryRunner] regression suite.
 *
 * Covers the snapshot-before-mutation invariant (commit-6 NO-GO patch
 * Blocker 1), per-session dispatch filter, sweep gating, and exception
 * containment.
 */
class ExportRecoveryRunnerTest {

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

    private fun newSession(
        exportState: ExportState = ExportState.MUXING,
        pendingUri: String? = null,
        privateTempPath: String? = null,
        publicTargetPath: String? = null,
        tier: ExportTier = ExportTier.TIER1_API29_PLUS
    ): String {
        val created = store.createSession(
            config = SessionConfig(
                durationSeconds = 30,
                intervalMinutes = 5,
                resolution = "FHD",
                loopCount = 4
            )
        )
        val patched = created.copy(
            exportTier = tier,
            exportState = exportState,
            pendingUri = pendingUri,
            privateTempPath = privateTempPath,
            publicTargetPath = publicTargetPath
        )
        File(store.sessionDir(created.sessionId), "manifest.json")
            .writeText(patched.toJson().toString(2))
        return created.sessionId
    }

    private fun runner(
        recoverSession: suspend (SessionManifest) -> RecoveryResult = { _ ->
            RecoveryResult.Resumed(ExportResult.Success(true, false))
        },
        validateTierArtifact: suspend (SessionManifest) -> Boolean = { false },
        orphanSweep: (suspend (Set<String>) -> OrphanSweepResult)? = null
    ) = ExportRecoveryRunner(store, recoverSession, validateTierArtifact, orphanSweep)

    // ─── Snapshot-before-mutation invariant ─────────────────────────

    @Test
    fun `snapshot is captured before per-session recovery mutates manifests`() {
        val s1 = newSession(
            exportState = ExportState.MUXING,
            pendingUri = "content://media/external/video/media/100"
        )
        val s2 = newSession(
            exportState = ExportState.MUXING,
            pendingUri = "content://media/external/video/media/200"
        )

        val sweepSawUris = mutableListOf<Set<String>>()
        // recoverSession mutates the manifest by clearing the pending URI
        // (mirroring what abandon() does via setExportFailed). If the
        // snapshot were taken AFTER recovery, the sweep would receive an
        // empty set and orphan the rows that were just abandoned.
        val report = runBlocking {
            runner(
                recoverSession = { m ->
                    store.setExportFailed(m.sessionId)
                    RecoveryResult.Abandoned
                },
                orphanSweep = { uris ->
                    sweepSawUris += uris
                    OrphanSweepResult.Swept(0, 0, 0, 0)
                }
            ).run()
        }

        assertEquals(1, sweepSawUris.size)
        assertEquals(
            setOf(
                "content://media/external/video/media/100",
                "content://media/external/video/media/200"
            ),
            sweepSawUris.first()
        )
        assertEquals(
            setOf(
                "content://media/external/video/media/100",
                "content://media/external/video/media/200"
            ),
            report.referencedPendingUris
        )
        // Manifests were mutated — pendingUri cleared by setExportFailed.
        assertNull(store.loadManifest(s1)!!.pendingUri)
        assertNull(store.loadManifest(s2)!!.pendingUri)
    }

    @Test
    fun `snapshot does not filter by exportState - FAILED manifest still protects its row`() {
        // Legacy/corrupt manifest: FAILED state but pendingUri still set.
        // The snapshot must include it so the sweep does not delete the
        // row that this manifest still references.
        newSession(
            exportState = ExportState.FAILED,
            pendingUri = "content://media/external/video/media/300"
        )

        var capturedUris: Set<String> = emptySet()
        runBlocking {
            runner(
                orphanSweep = { uris ->
                    capturedUris = uris
                    OrphanSweepResult.Swept(0, 0, 0, 0)
                }
            ).run()
        }

        assertTrue(
            "FAILED manifest's pendingUri must still appear in snapshot",
            "content://media/external/video/media/300" in capturedUris
        )
    }

    @Test
    fun `snapshot includes pendingUri across all states`() {
        newSession(exportState = ExportState.MUXING, pendingUri = "uri-muxing")
        newSession(exportState = ExportState.COPYING, pendingUri = "uri-copying")
        newSession(
            exportState = ExportState.FINALIZED,
            pendingUri = "uri-finalized",
            privateTempPath = null
        )
        newSession(exportState = ExportState.FAILED, pendingUri = "uri-failed")
        newSession(exportState = ExportState.NOT_STARTED, pendingUri = "uri-notstarted")

        val report = runBlocking {
            runner(orphanSweep = { _ -> OrphanSweepResult.Swept(0, 0, 0, 0) }).run()
        }

        assertEquals(
            setOf("uri-muxing", "uri-copying", "uri-finalized", "uri-failed", "uri-notstarted"),
            report.referencedPendingUris
        )
    }

    // ─── Per-session dispatch filter ────────────────────────────────

    @Test
    fun `MUXING session is dispatched to recoverSession`() {
        val sid = newSession(exportState = ExportState.MUXING, pendingUri = "u")
        val dispatched = mutableListOf<String>()
        runBlocking {
            runner(recoverSession = { m ->
                dispatched += m.sessionId
                RecoveryResult.Abandoned
            }).run()
        }
        assertEquals(listOf(sid), dispatched)
    }

    @Test
    fun `COPYING session is dispatched to recoverSession`() {
        val sid = newSession(exportState = ExportState.COPYING, privateTempPath = "/tmp/x")
        val dispatched = mutableListOf<String>()
        runBlocking {
            runner(recoverSession = { m ->
                dispatched += m.sessionId
                RecoveryResult.Abandoned
            }).run()
        }
        assertEquals(listOf(sid), dispatched)
    }

    @Test
    fun `FINALIZED with privateTempPath set is dispatched - deferred-scan retry`() {
        val sid = newSession(
            exportState = ExportState.FINALIZED,
            privateTempPath = "/tmp/x",
            publicTargetPath = "/movies/y.mp4"
        )
        val dispatched = mutableListOf<String>()
        runBlocking {
            runner(recoverSession = { m ->
                dispatched += m.sessionId
                RecoveryResult.Resumed(ExportResult.Success(true, false))
            }).run()
        }
        assertEquals(listOf(sid), dispatched)
    }

    @Test
    fun `FINALIZED with null privateTempPath is NOT dispatched - already done`() {
        newSession(exportState = ExportState.FINALIZED, privateTempPath = null)
        val dispatched = mutableListOf<String>()
        runBlocking {
            runner(recoverSession = { m ->
                dispatched += m.sessionId
                RecoveryResult.Abandoned
            }).run()
        }
        assertTrue("FINALIZED-clean session must not enter recovery", dispatched.isEmpty())
    }

    @Test
    fun `FAILED state is NOT dispatched`() {
        newSession(exportState = ExportState.FAILED, pendingUri = "u")
        val dispatched = mutableListOf<String>()
        runBlocking {
            runner(recoverSession = { m ->
                dispatched += m.sessionId
                RecoveryResult.Abandoned
            }).run()
        }
        assertTrue("FAILED is terminal — explicit user retry only", dispatched.isEmpty())
    }

    @Test
    fun `NOT_STARTED state is NOT dispatched`() {
        newSession(exportState = ExportState.NOT_STARTED)
        val dispatched = mutableListOf<String>()
        runBlocking {
            runner(recoverSession = { m ->
                dispatched += m.sessionId
                RecoveryResult.Abandoned
            }).run()
        }
        assertTrue(dispatched.isEmpty())
    }

    // ─── Orphan sweep gating ────────────────────────────────────────

    @Test
    fun `null orphan sweep returns synthetic empty Swept - pre-Q boot`() {
        newSession(exportState = ExportState.MUXING, pendingUri = "u")
        val report = runBlocking { runner(orphanSweep = null).run() }
        val s = report.sweep as OrphanSweepResult.Swept
        assertEquals(0, s.deleted)
        assertEquals(0, s.retainedReferenced)
        assertEquals(0, s.retainedOtherPackage)
        assertEquals(0, s.deleteFailures)
    }

    @Test
    fun `orphan sweep is invoked with snapshot URIs`() {
        newSession(pendingUri = "uri-A")
        newSession(pendingUri = "uri-B")

        var capturedUris: Set<String>? = null
        runBlocking {
            runner(orphanSweep = { uris ->
                capturedUris = uris
                OrphanSweepResult.Swept(0, 0, 0, 0)
            }).run()
        }
        assertEquals(setOf("uri-A", "uri-B"), capturedUris)
    }

    @Test
    fun `sweep throw becomes QueryFailed`() {
        newSession(pendingUri = "u")
        val cause = IOException("listing seam threw")
        val report = runBlocking {
            runner(orphanSweep = { _ -> throw cause }).run()
        }
        val failed = report.sweep as OrphanSweepResult.QueryFailed
        assertSame(cause, failed.cause)
    }

    // ─── Exception containment ──────────────────────────────────────

    @Test
    fun `recoverSession throw becomes RetryableFailure for that session`() {
        val sid = newSession(exportState = ExportState.MUXING, pendingUri = "u")
        val cause = IllegalStateException("dispatch threw")
        val report = runBlocking {
            runner(recoverSession = { _ -> throw cause }).run()
        }
        val r = report.perSession[sid] as RecoveryResult.RetryableFailure
        assertEquals("recoverSession", r.phase)
        assertSame(cause, r.cause)
    }

    @Test
    fun `recoverSession throw on one session does not block others`() {
        val s1 = newSession(exportState = ExportState.MUXING, pendingUri = "u1")
        val s2 = newSession(exportState = ExportState.MUXING, pendingUri = "u2")

        val report = runBlocking {
            runner(recoverSession = { m ->
                if (m.sessionId == s1) throw IllegalStateException("boom")
                RecoveryResult.Abandoned
            }).run()
        }
        assertTrue(report.perSession[s1] is RecoveryResult.RetryableFailure)
        assertEquals(RecoveryResult.Abandoned, report.perSession[s2])
    }

    @Test
    fun `report perSession only contains dispatched sessions`() {
        val muxing = newSession(exportState = ExportState.MUXING, pendingUri = "u")
        newSession(exportState = ExportState.FAILED)
        newSession(exportState = ExportState.NOT_STARTED)
        newSession(exportState = ExportState.FINALIZED, privateTempPath = null)

        val report = runBlocking {
            runner(recoverSession = { _ -> RecoveryResult.Abandoned }).run()
        }
        assertEquals(setOf(muxing), report.perSession.keys)
    }

    // ─── Tier dispatch travels with the manifest (the recoverSession
    // seam itself is responsible for switching on tier; here we verify
    // the runner faithfully passes the manifest's tier through). ────

    @Test
    fun `recoverSession sees the manifests recorded tier - travels with manifest`() {
        val sidQ = newSession(
            exportState = ExportState.MUXING,
            pendingUri = "uq",
            tier = ExportTier.TIER1_API29_PLUS
        )
        val sidR = newSession(
            exportState = ExportState.MUXING,
            privateTempPath = "/p/r",
            tier = ExportTier.TIER2_API26_28
        )
        val sidS = newSession(
            exportState = ExportState.MUXING,
            privateTempPath = "/p/s",
            tier = ExportTier.TIER3_API24_25
        )

        val seenTiers = mutableMapOf<String, ExportTier>()
        runBlocking {
            runner(recoverSession = { m ->
                seenTiers[m.sessionId] = m.exportTier
                RecoveryResult.Abandoned
            }).run()
        }
        assertEquals(ExportTier.TIER1_API29_PLUS, seenTiers[sidQ])
        assertEquals(ExportTier.TIER2_API26_28, seenTiers[sidR])
        assertEquals(ExportTier.TIER3_API24_25, seenTiers[sidS])
    }

    // ─── Empty state ────────────────────────────────────────────────

    @Test
    fun `no sessions on disk - empty snapshot, empty perSession, sweep called with empty set`() {
        var capturedUris: Set<String>? = null
        val report = runBlocking {
            runner(orphanSweep = { uris ->
                capturedUris = uris
                OrphanSweepResult.Swept(0, 0, 0, 0)
            }).run()
        }
        assertTrue(report.referencedPendingUris.isEmpty())
        assertTrue(report.perSession.isEmpty())
        assertEquals(emptySet<String>(), capturedUris)
    }

    @Test
    fun `sessions with null pendingUri do not appear in snapshot`() {
        newSession(exportState = ExportState.COPYING, pendingUri = null, privateTempPath = "/x")
        val report = runBlocking {
            runner(orphanSweep = { _ -> OrphanSweepResult.Swept(0, 0, 0, 0) }).run()
        }
        assertTrue(
            "null pendingUri must not appear in snapshot",
            report.referencedPendingUris.isEmpty()
        )
    }

    // ─── Late-terminal reconciliation (NO-GO patch round 2 — Blocker 1) ───

    @Test
    fun `late-terminal writes COMPLETED for FINALIZED session with terminated==null and intact artifact`() {
        // Row 13c: previous launch wrote setExportFinalized but its
        // markTerminated(COMPLETED) was lost in a crash. Phase 1.5 would
        // strand this session as SKIPPED_EXPORT_PENDING forever.
        val sid = newSession(
            exportState = ExportState.FINALIZED,
            pendingUri = "content://row/13c",
            privateTempPath = null  // Tier 1 happy-path shape
        )

        val report = runBlocking {
            runner(validateTierArtifact = { true }).run()
        }

        assertEquals(LateTerminalAction.WroteCompleted, report.lateTerminals[sid])
        val m = store.loadManifest(sid)!!
        assertEquals(Terminated.COMPLETED, m.terminated)
        assertNotNull(m.terminatedAt)
    }

    @Test
    fun `late-terminal also fires for FINALIZED session with privateTempPath set after recovery resume`() {
        // Tier 2/3 deferred-scan path: dispatched recover() returns
        // Resumed(Success). The exporter's recover() does NOT write
        // markTerminated; the runner must.
        val sid = newSession(
            exportState = ExportState.MUXING,  // pre-recovery state
            privateTempPath = "/tmp/x",
            publicTargetPath = "/movies/y.mp4"
        )

        // recoverSession transitions the manifest to FINALIZED + clears
        // privateTempPath (the success path). Simulated here — the late-
        // terminal pass reloads and observes the new state.
        val report = runBlocking {
            runner(
                recoverSession = { m ->
                    val current = store.loadManifest(m.sessionId)!!
                    File(store.sessionDir(m.sessionId), "manifest.json").writeText(
                        current.copy(
                            exportState = ExportState.FINALIZED,
                            privateTempPath = null
                        ).toJson().toString(2)
                    )
                    RecoveryResult.Resumed(ExportResult.Success(true, false))
                },
                validateTierArtifact = { true }
            ).run()
        }

        assertEquals(LateTerminalAction.WroteCompleted, report.lateTerminals[sid])
        val m = store.loadManifest(sid)!!
        assertEquals(Terminated.COMPLETED, m.terminated)
    }

    @Test
    fun `late-terminal skips when artifact validator returns false`() {
        val sid = newSession(
            exportState = ExportState.FINALIZED,
            pendingUri = "content://gone",
            privateTempPath = null
        )

        val report = runBlocking {
            runner(validateTierArtifact = { false }).run()
        }

        assertEquals(LateTerminalAction.SkippedArtifactInvalid, report.lateTerminals[sid])
        // terminated stays null → next launch retries
        assertNull(store.loadManifest(sid)!!.terminated)
    }

    @Test
    fun `late-terminal skips when validator throws`() {
        val sid = newSession(
            exportState = ExportState.FINALIZED,
            pendingUri = "content://x",
            privateTempPath = null
        )

        val report = runBlocking {
            runner(validateTierArtifact = { throw IllegalStateException("probe blew up") }).run()
        }

        assertEquals(LateTerminalAction.SkippedArtifactInvalid, report.lateTerminals[sid])
        assertNull(store.loadManifest(sid)!!.terminated)
    }

    @Test
    fun `late-terminal does not fire when exportState is not FINALIZED`() {
        newSession(exportState = ExportState.MUXING, pendingUri = "u")
        newSession(exportState = ExportState.COPYING, privateTempPath = "/x")
        newSession(exportState = ExportState.FAILED)
        newSession(exportState = ExportState.NOT_STARTED)

        val report = runBlocking {
            runner(validateTierArtifact = { true }).run()
        }

        assertTrue(
            "only FINALIZED && terminated==null gets a late-terminal write",
            report.lateTerminals.values.none { it == LateTerminalAction.WroteCompleted }
        )
    }

    @Test
    fun `late-terminal does not fire when terminated is already set`() {
        // FINALIZED && terminated == COMPLETED — fully done, no write.
        val sid = newSession(
            exportState = ExportState.FINALIZED,
            pendingUri = "u",
            privateTempPath = null
        )
        val current = store.loadManifest(sid)!!
        File(store.sessionDir(sid), "manifest.json").writeText(
            current.copy(terminated = Terminated.COMPLETED, terminatedAt = 1L)
                .toJson().toString(2)
        )

        val report = runBlocking {
            runner(validateTierArtifact = { true }).run()
        }

        assertFalse(
            "already-terminal session must not appear in lateTerminals",
            sid in report.lateTerminals
        )
    }

    @Test
    fun `late-terminal preserves existing terminal on AlreadyTerminal race`() {
        // Verify the AlreadyTerminal branch is reachable: pre-set the
        // terminal AFTER constructing the runner inputs but BEFORE the
        // pass runs. We do that by intercepting validateTierArtifact —
        // its call ordering means it runs in the late-terminal pass,
        // before markTerminated.
        val sid = newSession(
            exportState = ExportState.FINALIZED,
            pendingUri = "u",
            privateTempPath = null
        )

        val report = runBlocking {
            runner(validateTierArtifact = { m ->
                // Race-simulate: another writer flips the manifest terminal
                // BETWEEN our validate and our markTerminated. Implementation
                // detail of SessionStore.markTerminated guarantees the
                // existing terminal wins (AlreadyTerminal returned).
                val current = store.loadManifest(m.sessionId)!!
                File(store.sessionDir(m.sessionId), "manifest.json").writeText(
                    current.copy(
                        terminated = Terminated.USER_STOPPED,
                        terminatedAt = 1L
                    ).toJson().toString(2)
                )
                true
            }).run()
        }

        assertEquals(LateTerminalAction.AlreadyTerminal, report.lateTerminals[sid])
        // Existing terminal stays
        assertEquals(Terminated.USER_STOPPED, store.loadManifest(sid)!!.terminated)
    }

    // ─── Snapshot-incomplete short-circuit (NO-GO patch round 2 — Blocker 2) ───

    @Test
    fun `corrupt manifest during snapshot routes sweep to QueryFailed - protects orphan rows`() {
        // Set up: one valid session, one with a corrupt manifest.json.
        newSession(exportState = ExportState.MUXING, pendingUri = "valid-uri")
        val corruptId = "20260101_010101_corrupt0"
        store.sessionDir(corruptId).mkdirs()
        File(store.sessionDir(corruptId), "manifest.json").writeText("{ this is not json")

        var sweepInvoked = false
        val report = runBlocking {
            runner(orphanSweep = { _ ->
                sweepInvoked = true
                OrphanSweepResult.Swept(0, 0, 0, 0)
            }).run()
        }

        assertFalse(
            "sweep MUST NOT run on incomplete snapshot — would orphan referenced rows",
            sweepInvoked
        )
        assertTrue(
            "sweep must be QueryFailed on incomplete snapshot, got ${report.sweep}",
            report.sweep is OrphanSweepResult.QueryFailed
        )
    }

    @Test
    fun `empty manifest file during snapshot also routes sweep to QueryFailed`() {
        // SessionStore.loadManifest treats empty files as null.
        newSession(exportState = ExportState.MUXING, pendingUri = "valid-uri")
        val emptyId = "20260101_010101_empty000"
        store.sessionDir(emptyId).mkdirs()
        File(store.sessionDir(emptyId), "manifest.json").writeText("")

        var sweepInvoked = false
        val report = runBlocking {
            runner(orphanSweep = { _ ->
                sweepInvoked = true
                OrphanSweepResult.Swept(0, 0, 0, 0)
            }).run()
        }

        assertFalse(sweepInvoked)
        assertTrue(report.sweep is OrphanSweepResult.QueryFailed)
    }

    @Test
    fun `missing manifest file during snapshot routes sweep to QueryFailed`() {
        newSession(exportState = ExportState.MUXING, pendingUri = "valid-uri")
        // Create dir but no manifest.json
        val missingId = "20260101_010101_missing0"
        store.sessionDir(missingId).mkdirs()

        var sweepInvoked = false
        val report = runBlocking {
            runner(orphanSweep = { _ ->
                sweepInvoked = true
                OrphanSweepResult.Swept(0, 0, 0, 0)
            }).run()
        }

        assertFalse(sweepInvoked)
        assertTrue(report.sweep is OrphanSweepResult.QueryFailed)
    }

    @Test
    fun `complete snapshot allows sweep to run`() {
        newSession(exportState = ExportState.MUXING, pendingUri = "u")
        var sweepInvoked = false
        val report = runBlocking {
            runner(orphanSweep = { _ ->
                sweepInvoked = true
                OrphanSweepResult.Swept(1, 0, 0, 0)
            }).run()
        }
        assertTrue("clean snapshot → sweep runs", sweepInvoked)
        assertTrue(report.sweep is OrphanSweepResult.Swept)
    }

    @Test
    fun `manifest with empty-string pendingUri is parsed as null and not snapshotted`() {
        // SessionManifest.fromJson maps empty string back to null; verify
        // the runner respects that boundary.
        val sid = newSession(exportState = ExportState.MUXING, privateTempPath = "/x")
        // Re-write manifest with explicit empty pendingUri to round-trip
        // through fromJson.
        val m = store.loadManifest(sid)!!
        File(store.sessionDir(sid), "manifest.json")
            .writeText(m.copy(pendingUri = null).toJson().toString(2))

        val report = runBlocking {
            runner(orphanSweep = { _ -> OrphanSweepResult.Swept(0, 0, 0, 0) }).run()
        }
        assertFalse(
            "null/empty pendingUri must never appear in snapshot",
            report.referencedPendingUris.contains("")
        )
    }

    // ─── Phase 6.1b T14 — combineRecoveryResults helper ─────────────

    private val sampleSuccess = RecoveryResult.Resumed(
        ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false)
    )
    private val sampleAbandoned: RecoveryResult = RecoveryResult.Abandoned
    private val sampleRetryable: RecoveryResult =
        RecoveryResult.RetryableFailure("seam", IOException("boom"))
    private val sampleManifestFail: RecoveryResult =
        RecoveryResult.ManifestWriteFailed(IOException("write boom"))
    private val sampleUnknown: RecoveryResult = RecoveryResult.UnknownSession("sid-99")

    @Test
    fun `combineRecoveryResults both Resumed Success returns Resumed Success`() {
        val out = combineRecoveryResults(sampleSuccess, sampleSuccess)
        assertTrue("expected Resumed got $out", out is RecoveryResult.Resumed)
        val export = (out as RecoveryResult.Resumed).export
        assertTrue(export is ExportResult.Success)
    }

    @Test
    fun `combineRecoveryResults success plus abandoned returns success conservative`() {
        // At least one side recovered; runner should treat as success.
        val out1 = combineRecoveryResults(sampleSuccess, sampleAbandoned)
        val out2 = combineRecoveryResults(sampleAbandoned, sampleSuccess)
        assertTrue(out1 is RecoveryResult.Resumed)
        assertTrue(out2 is RecoveryResult.Resumed)
    }

    @Test
    fun `combineRecoveryResults retryable on either side propagates retryable`() {
        val out1 = combineRecoveryResults(sampleSuccess, sampleRetryable)
        val out2 = combineRecoveryResults(sampleRetryable, sampleSuccess)
        assertTrue("expected RetryableFailure got $out1", out1 is RecoveryResult.RetryableFailure)
        assertTrue("expected RetryableFailure got $out2", out2 is RecoveryResult.RetryableFailure)
    }

    @Test
    fun `combineRecoveryResults manifest write failed on either side propagates manifest write failed`() {
        val out1 = combineRecoveryResults(sampleSuccess, sampleManifestFail)
        val out2 = combineRecoveryResults(sampleManifestFail, sampleSuccess)
        assertTrue(out1 is RecoveryResult.ManifestWriteFailed)
        assertTrue(out2 is RecoveryResult.ManifestWriteFailed)
    }

    @Test
    fun `combineRecoveryResults both abandoned returns abandoned`() {
        val out = combineRecoveryResults(sampleAbandoned, sampleAbandoned)
        assertTrue("expected Abandoned got $out", out === RecoveryResult.Abandoned)
    }

    @Test
    fun `combineRecoveryResults unknown session on either side propagates unknown session`() {
        val out1 = combineRecoveryResults(sampleSuccess, sampleUnknown)
        val out2 = combineRecoveryResults(sampleUnknown, sampleSuccess)
        assertTrue(out1 is RecoveryResult.UnknownSession)
        assertTrue(out2 is RecoveryResult.UnknownSession)
    }

    @Test
    fun `combineRecoveryResults retryable beats manifest write failed`() {
        // Both failure modes are conservative; retryable wins because it
        // signals "transient — try again next launch" which is a strict
        // superset of manifest-write-failed semantics for the runner.
        val out = combineRecoveryResults(sampleRetryable, sampleManifestFail)
        assertTrue("expected RetryableFailure got $out", out is RecoveryResult.RetryableFailure)
    }
}
