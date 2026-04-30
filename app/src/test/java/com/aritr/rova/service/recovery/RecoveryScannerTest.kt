package com.aritr.rova.service.recovery

import com.aritr.rova.data.AudioMode
import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.MarkTerminatedResult
import com.aritr.rova.data.SegmentRecord
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import com.aritr.rova.utils.MediaFileInspection
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 1.5 / ADR 0005 unit tests. Uses a temp-dir-backed [SessionStore]
 * (the test-only secondary constructor) and injected [MediaFileInspection]
 * + live-session callbacks on [RecoveryScanner] so the matrix can be
 * exercised without Robolectric, MediaExtractor, or SHA-1 of real media.
 */
class RecoveryScannerTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var store: SessionStore
    private var liveSessionId: String? = null
    private var nowMillis: Long = BASE_NOW

    private val inspectionsByName = mutableMapOf<String, MediaFileInspection>()

    @Before
    fun setUp() {
        rootDir = tmp.newFolder("videos")
        store = SessionStore(rootDir)
        liveSessionId = null
        nowMillis = BASE_NOW
        inspectionsByName.clear()
    }

    private fun newScanner(): RecoveryScanner = RecoveryScanner(
        sessionStore = store,
        now = { nowMillis },
        inspect = { f ->
            inspectionsByName[f.name] ?: MediaFileInspection(isValid = true, durationMs = 1_000L)
        },
        liveSessionId = { liveSessionId },
        sha1 = { "deadbeef" }
    )

    // -- Test fixtures -------------------------------------------------------

    /**
     * Construct an on-disk session directory with the given manifest
     * (written via the same atomic-replace path as production).
     * [diskSegmentNames] places empty placeholder files for segments — the
     * scanner uses the injected `inspect` lambda for validity, so size/zero
     * doesn't matter to the test outcome.
     */
    private fun seedSession(
        sessionId: String,
        manifestSegments: List<String> = emptyList(),
        diskSegmentNames: List<String> = manifestSegments,
        terminated: Terminated? = null,
        stopRequested: Boolean = false,
        startedAt: Long = SESSION_STARTED_AT,
        otherFiles: List<String> = emptyList(),
        exportState: ExportState = ExportState.NOT_STARTED,
        stopReason: StopReason = StopReason.NONE,
        audioMode: AudioMode = AudioMode.VIDEO_ONLY
    ) {
        val sessionDir = File(rootDir, sessionId).also { it.mkdirs() }
        diskSegmentNames.forEach { name -> File(sessionDir, name).writeBytes(byteArrayOf(0x00)) }
        otherFiles.forEach { name -> File(sessionDir, name).writeBytes(byteArrayOf(0x00)) }
        val manifest = SessionManifest(
            sessionId = sessionId,
            startedAt = startedAt,
            config = SessionConfig(durationSeconds = 5, intervalMinutes = 1, resolution = "720p", loopCount = 0),
            segments = manifestSegments.mapIndexed { i, name ->
                SegmentRecord(filename = name, durationMs = 1_000L, sizeBytes = 1L, sha1 = "sha-$i")
            },
            exportTier = ExportTier.TIER1_API29_PLUS,
            exportState = exportState,
            terminated = terminated,
            terminatedAt = terminated?.let { 1L },
            stopRequested = stopRequested,
            stopReason = stopReason,
            audioMode = audioMode
        )
        File(sessionDir, "manifest.json").writeText(manifest.toJson().toString())
    }

    private fun reloadManifest(sessionId: String): SessionManifest {
        val file = File(rootDir, "$sessionId/manifest.json")
        return SessionManifest.fromJson(JSONObject(file.readText()))
    }

    // -- Decision matrix tests ----------------------------------------------

    /**
     * ADR 0005 — Decision Matrix row "T = COMPLETED": even with valid
     * contiguous orphans, the scanner MUST NOT append. Stray segment files
     * surface as `OrphanSegmentAnomaly` for human review only.
     *
     * This is the "hard no-append" test the user explicitly required.
     */
    @Test
    fun `COMPLETED session does not append even with valid contiguous orphan`() = runBlocking {
        val sid = "completed-session"
        seedSession(
            sessionId = sid,
            manifestSegments = listOf("segment_0001.mp4", "segment_0002.mp4"),
            diskSegmentNames = listOf("segment_0001.mp4", "segment_0002.mp4", "segment_0003.mp4"),
            terminated = Terminated.COMPLETED
        )

        val classification = newScanner().classify(sid, nowMillis)

        assertEquals(TerminalAction.ALREADY_TERMINAL, classification.terminalAction)
        assertTrue(
            "appendedSegmentFilenames must be empty for COMPLETED sessions; got ${classification.appendedSegmentFilenames}",
            classification.appendedSegmentFilenames.isEmpty()
        )
        assertTrue(
            "OrphanSegment anomaly with index 3 expected; got ${classification.anomalies}",
            classification.anomalies.any { it is Anomaly.OrphanSegment && it.indices == listOf(3) }
        )
        // Manifest segments unchanged.
        val finalManifest = reloadManifest(sid)
        assertEquals(2, finalManifest.segments.size)
        assertEquals(Terminated.COMPLETED, finalManifest.terminated)
    }

    /**
     * ADR 0005 — Decision Matrix row 5: T=null AND stopRequested=true AND
     * empty session → markTerminated(USER_STOPPED). This is the Phase 1.3
     * prep-time stop carry-over (memory: project_phase15_recovery_carryover).
     */
    @Test
    fun `T null with stopRequested and empty session writes USER_STOPPED`() = runBlocking {
        val sid = "prep-time-stop"
        seedSession(sessionId = sid, manifestSegments = emptyList(), stopRequested = true)

        val classification = newScanner().classify(sid, nowMillis)

        assertEquals(TerminalAction.WROTE_USER_STOPPED, classification.terminalAction)
        assertEquals(DiscardEligibility.AUTO_DISCARD_ELIGIBLE, classification.eligibility)
        assertTrue("anomalies must be empty; got ${classification.anomalies}", classification.anomalies.isEmpty())
        assertEquals(Terminated.USER_STOPPED, reloadManifest(sid).terminated)
    }

    /**
     * ADR 0005 — Decision Matrix row 7: T=null AND stopRequested=false AND
     * empty session → markTerminated(KILLED_FORCE_STOP).
     */
    @Test
    fun `T null no stopRequested and empty session writes KILLED_FORCE_STOP`() = runBlocking {
        val sid = "force-stopped"
        seedSession(sessionId = sid)

        val classification = newScanner().classify(sid, nowMillis)

        assertEquals(TerminalAction.WROTE_KILLED_FORCE_STOP, classification.terminalAction)
        assertEquals(Terminated.KILLED_FORCE_STOP, reloadManifest(sid).terminated)
    }

    /**
     * ADR 0005 — Append-before-markTerminated invariant. With T=null and
     * a valid contiguous orphan, the scanner appends first, then writes
     * the terminal value. Verifies the manifest reflects both.
     */
    @Test
    fun `T null appends contiguous orphan prefix and then writes terminal`() = runBlocking {
        val sid = "appendable"
        seedSession(
            sessionId = sid,
            manifestSegments = listOf("segment_0001.mp4"),
            diskSegmentNames = listOf("segment_0001.mp4", "segment_0002.mp4")
        )

        val classification = newScanner().classify(sid, nowMillis)

        assertEquals(TerminalAction.WROTE_KILLED_FORCE_STOP, classification.terminalAction)
        assertEquals(listOf("segment_0002.mp4"), classification.appendedSegmentFilenames)

        val finalManifest = reloadManifest(sid)
        assertEquals(
            listOf("segment_0001.mp4", "segment_0002.mp4"),
            finalManifest.segments.map { it.filename }
        )
        assertEquals(Terminated.KILLED_FORCE_STOP, finalManifest.terminated)
    }

    /**
     * ADR 0005 §"Concurrency Invariants" item 5 — per-session live re-check.
     * If the session is owned by a live ServiceController, the scanner
     * skips it entirely (no terminal write, no append).
     */
    @Test
    fun `live-owned session is skipped`() = runBlocking {
        val sid = "live-session"
        seedSession(sessionId = sid)
        liveSessionId = sid

        val classification = newScanner().classify(sid, nowMillis)

        assertEquals(TerminalAction.SKIPPED, classification.terminalAction)
        assertEquals(DiscardEligibility.BLOCKED, classification.eligibility)
        assertNull("manifest.terminated must remain null for live-owned sessions", reloadManifest(sid).terminated)
    }

    /**
     * ADR 0005 §"Concurrency Invariants" item 6 — age filter. A session
     * whose startedAt is within the 5 s window (relative to scan start)
     * is skipped to avoid racing newly-started recordings.
     */
    @Test
    fun `recently-started session is age-filtered`() = runBlocking {
        val sid = "recent-session"
        seedSession(sessionId = sid, startedAt = nowMillis - 1_000L)

        val classification = newScanner().classify(sid, nowMillis)

        assertEquals(TerminalAction.SKIPPED, classification.terminalAction)
        assertNull(reloadManifest(sid).terminated)
    }

    /**
     * Orphan past the first gap is NOT appended; surfaces as anomaly.
     */
    @Test
    fun `orphan past gap is anomaly not appended`() = runBlocking {
        val sid = "gap-session"
        seedSession(
            sessionId = sid,
            manifestSegments = listOf("segment_0001.mp4"),
            diskSegmentNames = listOf("segment_0001.mp4", "segment_0003.mp4")
        )

        val classification = newScanner().classify(sid, nowMillis)

        assertTrue(classification.appendedSegmentFilenames.isEmpty())
        assertTrue(
            "OrphanSegment(3) expected; got ${classification.anomalies}",
            classification.anomalies.any { it is Anomaly.OrphanSegment && it.indices == listOf(3) }
        )
        assertEquals(1, reloadManifest(sid).segments.size)
    }

    /**
     * Manifest segment whose file fails [validateMediaFile] surfaces as
     * `InvalidManifestSegmentAnomaly` and forces OFFER_DISCARD.
     */
    @Test
    fun `invalid in-manifest segment emits InvalidManifestSegment anomaly`() = runBlocking {
        val sid = "corrupt-manifest"
        seedSession(
            sessionId = sid,
            manifestSegments = listOf("segment_0001.mp4", "segment_0002.mp4")
        )
        inspectionsByName["segment_0002.mp4"] = MediaFileInspection.INVALID

        val classification = newScanner().classify(sid, nowMillis)

        assertTrue(
            classification.anomalies.any { it is Anomaly.InvalidManifestSegment && it.indices == listOf(2) }
        )
        assertEquals(DiscardEligibility.OFFER_DISCARD, classification.eligibility)
    }

    /**
     * Unknown artifact (file matching neither manifest.json[.tmp] nor
     * the segment regex) emits anomaly and forces OFFER_DISCARD.
     */
    @Test
    fun `unknown artifact emits UnknownArtifact anomaly`() = runBlocking {
        val sid = "stray-files"
        seedSession(sessionId = sid, otherFiles = listOf("Rova_legacy.mp4"))

        val classification = newScanner().classify(sid, nowMillis)

        assertTrue(
            "UnknownArtifact(Rova_legacy.mp4) expected; got ${classification.anomalies}",
            classification.anomalies.any { it is Anomaly.UnknownArtifact && it.filenames == listOf("Rova_legacy.mp4") }
        )
        assertEquals(DiscardEligibility.OFFER_DISCARD, classification.eligibility)
    }

    /**
     * Append-before-markTerminated ordering — failure-injection variant.
     * Subclasses [SessionStore] to throw on `appendSegment`; verifies
     * `markTerminated` was never reached (manifest.terminated stays null).
     * Confirms the scanner does not pre-mark and then rely on append's
     * idempotency.
     */
    @Test
    fun `appendSegment failure prevents markTerminated`() {
        val sid = "ordering-test"
        seedSession(
            sessionId = sid,
            manifestSegments = listOf("segment_0001.mp4"),
            diskSegmentNames = listOf("segment_0001.mp4", "segment_0002.mp4")
        )
        val throwingStore = object : SessionStore(rootDir) {
            override suspend fun appendSegment(sessionId: String, record: SegmentRecord) {
                throw RuntimeException("forced for ordering test")
            }
        }
        val scanner = RecoveryScanner(
            sessionStore = throwingStore,
            now = { nowMillis },
            inspect = { MediaFileInspection(isValid = true, durationMs = 1_000L) },
            liveSessionId = { null },
            sha1 = { "sha" }
        )

        var caught: Throwable? = null
        try {
            runBlocking { scanner.classify(sid, nowMillis) }
        } catch (t: Throwable) {
            caught = t
        }
        assertTrue("expected appendSegment failure to propagate; got null", caught != null)
        assertNull(
            "markTerminated must NOT run when appendSegment throws (append-before-mark ordering)",
            reloadManifest(sid).terminated
        )
    }

    /**
     * Bug 1 (review round 5): manifest record whose filename does not match
     * the canonical regex `^segment_(\d{4})\.mp4$` MUST surface as
     * `MalformedManifestRecord` rather than being silently dropped via
     * `mapNotNull`. Without this, a malformed/corrupt record could be
     * lost entirely, and a session with no other surviving files would
     * incorrectly land at AUTO_DISCARD_ELIGIBLE.
     */
    @Test
    fun `non-canonical manifest record emits MalformedManifestRecord and blocks AUTO_DISCARD`() = runBlocking {
        val sid = "malformed-record"
        // Seed a manifest with a non-canonical filename. No matching disk
        // file — isolates the manifest-side anomaly.
        val sessionDir = File(rootDir, sid).also { it.mkdirs() }
        val malformed = SessionManifest(
            sessionId = sid,
            startedAt = SESSION_STARTED_AT,
            config = SessionConfig(durationSeconds = 5, intervalMinutes = 1, resolution = "720p", loopCount = 0),
            segments = listOf(
                SegmentRecord(filename = "weird_legacy_name.mp4", durationMs = 1_000L, sizeBytes = 1L, sha1 = "x")
            ),
            exportTier = ExportTier.TIER1_API29_PLUS,
            terminated = Terminated.USER_STOPPED,
            terminatedAt = 1L
        )
        File(sessionDir, "manifest.json").writeText(malformed.toJson().toString())

        val classification = newScanner().classify(sid, nowMillis)

        assertTrue(
            "MalformedManifestRecord(weird_legacy_name.mp4) expected; got ${classification.anomalies}",
            classification.anomalies.any {
                it is Anomaly.MalformedManifestRecord && it.filenames == listOf("weird_legacy_name.mp4")
            }
        )
        assertEquals(
            "Manifest with malformed records must never auto-discard",
            DiscardEligibility.OFFER_DISCARD,
            classification.eligibility
        )
    }

    /**
     * Bug 2 (review round 5): a valid orphan whose index is at or below
     * `Idx_max_manifest` (e.g., manifest claims indices 1 and 3, disk has
     * segment_0002.mp4) MUST surface as `OrphanSegmentAnomaly`. The earlier
     * "post-gap above max only" form silently lost this case, allowing a
     * session to land at OFFER_DISCARD with surviving evidence not
     * represented in `anomalies` or `appendedSegmentFilenames`.
     */
    @Test
    fun `valid orphan below idx_max emits OrphanSegment anomaly`() = runBlocking {
        val sid = "below-max-orphan"
        seedSession(
            sessionId = sid,
            manifestSegments = listOf("segment_0001.mp4", "segment_0003.mp4"),
            diskSegmentNames = listOf("segment_0001.mp4", "segment_0002.mp4", "segment_0003.mp4")
        )

        val classification = newScanner().classify(sid, nowMillis)

        assertTrue(
            "appendedSegmentFilenames must be empty (segment_0002.mp4 is below idx_max=3); got ${classification.appendedSegmentFilenames}",
            classification.appendedSegmentFilenames.isEmpty()
        )
        assertTrue(
            "OrphanSegment(2) expected for below-max valid orphan; got ${classification.anomalies}",
            classification.anomalies.any {
                it is Anomaly.OrphanSegment && it.indices == listOf(2)
            }
        )
        assertEquals(DiscardEligibility.OFFER_DISCARD, classification.eligibility)
        // Manifest unchanged.
        assertEquals(2, reloadManifest(sid).segments.size)
    }

    /**
     * Manifest-internal duplicates: two `SegmentRecord` entries with the
     * same parsed canonical index (allowed by the schema, nonsensical in
     * practice). The scan must report rather than silently keep the
     * last-wins entry.
     */
    @Test
    fun `manifest internal duplicate emits DuplicateSegment anomaly`() = runBlocking {
        val sid = "dup-records"
        val sessionDir = File(rootDir, sid).also { it.mkdirs() }
        File(sessionDir, "segment_0001.mp4").writeBytes(byteArrayOf(0x00))
        val duplicated = SessionManifest(
            sessionId = sid,
            startedAt = SESSION_STARTED_AT,
            config = SessionConfig(durationSeconds = 5, intervalMinutes = 1, resolution = "720p", loopCount = 0),
            segments = listOf(
                SegmentRecord(filename = "segment_0001.mp4", durationMs = 1_000L, sizeBytes = 1L, sha1 = "a"),
                SegmentRecord(filename = "segment_0001.mp4", durationMs = 1_000L, sizeBytes = 1L, sha1 = "b")
            ),
            exportTier = ExportTier.TIER1_API29_PLUS,
            terminated = Terminated.USER_STOPPED,
            terminatedAt = 1L
        )
        File(sessionDir, "manifest.json").writeText(duplicated.toJson().toString())

        val classification = newScanner().classify(sid, nowMillis)

        assertTrue(
            "DuplicateSegment(1) expected; got ${classification.anomalies}",
            classification.anomalies.any { it is Anomaly.DuplicateSegment && it.indices == listOf(1) }
        )
        assertEquals(DiscardEligibility.OFFER_DISCARD, classification.eligibility)
    }

    // -- ADR 0006 amendments (B14 + B17 + B22) ------------------------------

    /**
     * ADR 0006 §"Cross-Phase Ordering Invariant" (B14): a session with
     * `terminated == null && exportState == MUXING` is owned by Phase 1.7
     * export-recovery. Phase 1.5 must NOT write a terminal value;
     * classification yields SKIPPED_EXPORT_PENDING + BLOCKED.
     */
    @Test
    fun `T null with exportState MUXING yields SKIPPED_EXPORT_PENDING`() = runBlocking {
        val sid = "muxing-session"
        seedSession(
            sessionId = sid,
            manifestSegments = listOf("segment_0001.mp4"),
            diskSegmentNames = listOf("segment_0001.mp4"),
            exportState = ExportState.MUXING
        )

        val classification = newScanner().classify(sid, nowMillis)

        assertEquals(TerminalAction.SKIPPED_EXPORT_PENDING, classification.terminalAction)
        assertEquals(DiscardEligibility.BLOCKED, classification.eligibility)
        assertTrue(
            "anomalies must be empty (Phase 1.7 owns); got ${classification.anomalies}",
            classification.anomalies.isEmpty()
        )
        // No terminal write performed.
        assertNull(reloadManifest(sid).terminated)
    }

    /**
     * ADR 0006 B14: same as above for COPYING.
     */
    @Test
    fun `T null with exportState COPYING yields SKIPPED_EXPORT_PENDING`() = runBlocking {
        val sid = "copying-session"
        seedSession(sessionId = sid, exportState = ExportState.COPYING)

        val classification = newScanner().classify(sid, nowMillis)

        assertEquals(TerminalAction.SKIPPED_EXPORT_PENDING, classification.terminalAction)
        assertEquals(DiscardEligibility.BLOCKED, classification.eligibility)
        assertNull(reloadManifest(sid).terminated)
    }

    /**
     * ADR 0006 B14: row 13c — artifact committed, terminal-write pending.
     * Phase 1.7 must own the late `markTerminated(COMPLETED)`.
     */
    @Test
    fun `T null with exportState FINALIZED yields SKIPPED_EXPORT_PENDING`() = runBlocking {
        val sid = "finalized-pending"
        seedSession(sessionId = sid, exportState = ExportState.FINALIZED)

        val classification = newScanner().classify(sid, nowMillis)

        assertEquals(TerminalAction.SKIPPED_EXPORT_PENDING, classification.terminalAction)
        assertEquals(DiscardEligibility.BLOCKED, classification.eligibility)
        assertNull(reloadManifest(sid).terminated)
    }

    /**
     * ADR 0006 B17 — the load-bearing fix. `FAILED` is a Phase 1.5 INPUT,
     * not a Phase 1.7 input. The previous draft's `!= NOT_STARTED` guard
     * would have stranded these forever. Verify FAILED falls through to
     * the normal classification matrix (KILLED_FORCE_STOP for empty
     * stop-state).
     */
    @Test
    fun `T null with exportState FAILED is NOT skipped — falls through to KILLED_FORCE_STOP`() = runBlocking {
        val sid = "failed-export"
        seedSession(sessionId = sid, exportState = ExportState.FAILED)

        val classification = newScanner().classify(sid, nowMillis)

        // Must NOT be SKIPPED_EXPORT_PENDING.
        assertTrue(
            "FAILED exportState must NOT yield SKIPPED_EXPORT_PENDING (B17); got ${classification.terminalAction}",
            classification.terminalAction != TerminalAction.SKIPPED_EXPORT_PENDING
        )
        // Falls through: stopRequested=false, no signal → KILLED_FORCE_STOP.
        assertEquals(TerminalAction.WROTE_KILLED_FORCE_STOP, classification.terminalAction)
        assertEquals(Terminated.KILLED_FORCE_STOP, reloadManifest(sid).terminated)
    }

    /**
     * ADR 0006 B14: SKIPPED_EXPORT_PENDING is gated on T == null. A
     * terminal session with exportState in the in-flight set is already
     * Phase 1.5-classified (ALREADY_TERMINAL) — the export state alone
     * does NOT trigger the skip.
     */
    @Test
    fun `Terminal session with exportState MUXING is ALREADY_TERMINAL not SKIPPED_EXPORT_PENDING`() = runBlocking {
        val sid = "terminal-muxing"
        seedSession(
            sessionId = sid,
            terminated = Terminated.USER_STOPPED,
            exportState = ExportState.MUXING
        )

        val classification = newScanner().classify(sid, nowMillis)

        assertEquals(TerminalAction.ALREADY_TERMINAL, classification.terminalAction)
    }

    // -- ADR 0006 atomic terminal-write (B2 + B8 + B9) ---------------------

    /**
     * ADR 0006 B2: `markTerminated` writes both `terminated` and
     * `stopReason` atomically in a single manifest commit. Verify the
     * stopReason is persisted alongside.
     */
    @Test
    fun `markTerminated writes stopReason atomically with terminated`() = runBlocking {
        val sid = "atomic-write"
        seedSession(sessionId = sid)

        val result = store.markTerminated(sid, Terminated.USER_STOPPED, StopReason.PERMISSION_REVOKED)

        assertTrue("expected Wrote; got $result", result is MarkTerminatedResult.Wrote)
        val manifest = reloadManifest(sid)
        assertEquals(Terminated.USER_STOPPED, manifest.terminated)
        assertEquals(StopReason.PERMISSION_REVOKED, manifest.stopReason)
    }

    /**
     * ADR 0006 B2 first-writer-wins: the loser's stopReason is discarded;
     * the winner's pair stays. A racing terminal-write returns
     * AlreadyTerminal with the existing pair.
     */
    @Test
    fun `markTerminated first-writer-wins preserves winner stopReason`() = runBlocking {
        val sid = "race-loser"
        seedSession(sessionId = sid)

        val first = store.markTerminated(sid, Terminated.USER_STOPPED, StopReason.LOW_STORAGE)
        val second = store.markTerminated(sid, Terminated.USER_STOPPED, StopReason.PERMISSION_REVOKED)

        assertTrue("first must be Wrote; got $first", first is MarkTerminatedResult.Wrote)
        assertTrue("second must be AlreadyTerminal; got $second", second is MarkTerminatedResult.AlreadyTerminal)
        val existing = (second as MarkTerminatedResult.AlreadyTerminal)
        assertEquals(Terminated.USER_STOPPED, existing.existingTerminated)
        // Winner's reason wins, not the loser's.
        assertEquals(StopReason.LOW_STORAGE, existing.existingStopReason)
        // On-disk reflects winner.
        assertEquals(StopReason.LOW_STORAGE, reloadManifest(sid).stopReason)
    }

    /**
     * ADR 0006 B8: KILLED_FORCE_STOP write from `RecoveryScanner` no-
     * surviving-signal branch carries StopReason.NONE per migration table.
     */
    @Test
    fun `RecoveryScanner KILLED_FORCE_STOP carries StopReason NONE`() = runBlocking {
        val sid = "killed-force-stop"
        seedSession(sessionId = sid)

        newScanner().classify(sid, nowMillis)

        val manifest = reloadManifest(sid)
        assertEquals(Terminated.KILLED_FORCE_STOP, manifest.terminated)
        assertEquals(StopReason.NONE, manifest.stopReason)
    }

    /**
     * ADR 0006 B8: USER_STOPPED write from `RecoveryScanner`'s
     * stopRequested=true branch carries StopReason.USER (the user's prior
     * intent recorded on the manifest).
     */
    @Test
    fun `RecoveryScanner USER_STOPPED stopRequested branch carries StopReason USER`() = runBlocking {
        val sid = "stop-requested"
        seedSession(sessionId = sid, stopRequested = true)

        newScanner().classify(sid, nowMillis)

        val manifest = reloadManifest(sid)
        assertEquals(Terminated.USER_STOPPED, manifest.terminated)
        assertEquals(StopReason.USER, manifest.stopReason)
    }

    /**
     * ADR 0006 B21 + Phase 1.4 schema delta: SessionManifest round-trips
     * the new `audioMode` and `stopReason` fields through JSON.
     */
    @Test
    fun `SessionManifest round-trips audioMode and stopReason`() = runBlocking {
        val sid = "schema-roundtrip"
        seedSession(
            sessionId = sid,
            terminated = Terminated.USER_STOPPED,
            stopReason = StopReason.PERMISSION_REVOKED,
            audioMode = AudioMode.VIDEO_AUDIO
        )

        val manifest = reloadManifest(sid)
        assertEquals(StopReason.PERMISSION_REVOKED, manifest.stopReason)
        assertEquals(AudioMode.VIDEO_AUDIO, manifest.audioMode)
    }

    // -- ADR 0006 B9 — markTerminated.Failed caller contract --------------

    /**
     * ADR 0006 B9: `markTerminated` returns
     * [MarkTerminatedResult.Failed] when the manifest write throws after
     * exhausting the 3-attempt retry budget.
     *
     * Uses a subclass that injects IOException into the manifest write
     * path. This is the unit-level proof of the contract; service-level
     * integration (skip-merge + degraded notification) is covered by the
     * runtime test in ADR 0006 §"Acceptance Criteria".
     */
    @Test
    fun `markTerminated Failed is returned after retry exhaustion`() = runBlocking {
        val sid = "io-fail"
        seedSession(sessionId = sid)

        val faultyStore = object : SessionStore(rootDir) {
            override suspend fun markTerminated(
                sessionId: String,
                terminated: Terminated,
                stopReason: StopReason
            ): MarkTerminatedResult = MarkTerminatedResult.Failed(
                cause = java.io.IOException("synthetic disk-full"),
                attempts = SessionStore.MARK_TERMINATED_MAX_ATTEMPTS
            )
        }

        val result = faultyStore.markTerminated(
            sid, Terminated.USER_STOPPED, StopReason.LOW_STORAGE
        )

        assertTrue("expected Failed; got $result", result is MarkTerminatedResult.Failed)
        val failed = result as MarkTerminatedResult.Failed
        assertEquals(SessionStore.MARK_TERMINATED_MAX_ATTEMPTS, failed.attempts)
        assertTrue(
            "cause must be IOException; got ${failed.cause}",
            failed.cause is java.io.IOException
        )
        // Manifest stays non-terminal — no half-written terminal state.
        assertNull(reloadManifest(sid).terminated)
    }

    /**
     * ADR 0006 B9: unknown sessionId returns Failed (zero attempts) per
     * the public contract. Caller treats it the same as a write failure:
     * skip merge, defer to recovery.
     */
    @Test
    fun `markTerminated Failed for unknown sessionId`() = runBlocking {
        val result = store.markTerminated(
            "no-such-session", Terminated.USER_STOPPED, StopReason.USER
        )
        assertTrue("expected Failed; got $result", result is MarkTerminatedResult.Failed)
        assertEquals(0, (result as MarkTerminatedResult.Failed).attempts)
    }

    companion object {
        private const val BASE_NOW = 1_000_000_000L
        private const val SESSION_STARTED_AT = BASE_NOW - 60_000L  // well outside age filter
    }
}
