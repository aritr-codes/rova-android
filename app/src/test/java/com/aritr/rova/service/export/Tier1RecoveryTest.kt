package com.aritr.rova.service.export

import com.aritr.rova.data.ExportMutationResult
import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Phase 1.7 commit-4 — Tier 1 cold-launch recovery routine,
 * ADR 0003 §"Recovery routing" Tier 1 column.
 *
 * Recovery handles ONLY manifest-referenced `pendingUri` (per-session).
 * Direct URI operations only — no listing/query sweep (that is commit
 * 5's orphan sweep). Cases:
 * - null `pendingUri` → abandon (no row to clean).
 * - `validatePending` returns `false` → row deleted, manifest FAILED.
 * - `validatePending` returns `true` → finalize row (`IS_PENDING=0`),
 *   manifest FINALIZED with `pendingUri` retained.
 * - `finalize` returns `false` / throws after a valid probe → abandon.
 *
 * Tier guard: `recover()` rejects manifests whose `exportTier` isn't
 * TIER1_API29_PLUS — the Phase 1.7 dispatch matrix routes by recorded
 * tier, never running Tier 1 recovery against a Tier 2/3 manifest.
 */
class Tier1RecoveryTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var store: StubbingSessionStoreTier1
    private lateinit var sessionId: String
    private lateinit var initial: SessionManifest

    private val testUri = "content://media/external/video/media/77"

    @Before
    fun setUp() {
        store = StubbingSessionStoreTier1(tmp.newFolder("videos"))
        val created = store.createSession(
            config = SessionConfig(
                durationSeconds = 30,
                intervalSeconds = 300,
                resolution = "FHD",
                loopCount = 4
            )
        )
        sessionId = created.sessionId
        initial = created.copy(exportTier = ExportTier.TIER1_API29_PLUS)
    }

    @After
    fun tearDown() {
        store.close()
    }

    private fun writePreState(updated: SessionManifest) {
        val target = File(store.sessionDir(updated.sessionId), "manifest.json")
        target.writeText(updated.toJson().toString(2))
    }

    private fun reload(): SessionManifest =
        requireNotNull(store.loadManifest(sessionId)) { "manifest disappeared" }

    private fun tier1Manifest(
        pendingUri: String? = testUri,
        exportState: ExportState = ExportState.MUXING
    ): SessionManifest = initial.copy(
        exportTier = ExportTier.TIER1_API29_PLUS,
        pendingUri = pendingUri,
        exportState = exportState
    )

    private inner class Recorder {
        val events = mutableListOf<String>()
        var validateReturn: Boolean = true
        var validateThrow: Throwable? = null
        var finalizeResult: Tier1FinalizeResult = Tier1FinalizeResult.Finalized
        var deleteReturn: Boolean = true

        val validate: suspend (String) -> Boolean = { uri ->
            events += "validate($uri)"
            validateThrow?.let { throw it }
            validateReturn
        }
        val finalize: suspend (String) -> Tier1FinalizeResult = { uri ->
            events += "finalize($uri)"
            finalizeResult
        }
        val delete: suspend (String) -> Boolean = { uri ->
            events += "delete($uri)"
            deleteReturn
        }
    }

    private fun newExporter(r: Recorder): Tier1Exporter = Tier1Exporter(
        sessionStore = store,
        // Live-export seams — unused by recover() but required for ctor.
        insertPendingRow = { _ -> error("insertPendingRow must not be called from recover()") },
        withPendingFd = { _, _, _ -> error("withPendingFd must not be called from recover()") },
        mux = { _, _ -> error("mux must not be called from recover()") },
        finalizePendingRow = r.finalize,
        deletePendingRow = r.delete,
        validatePending = r.validate
    )

    // ─── Pending row missing (null pointer) ─────────────────────────

    @Test
    fun `null pendingUri - abandon FAILED no validate no delete`() {
        val r = Recorder()
        val pre = tier1Manifest(pendingUri = null)
        writePreState(pre)

        val result = runBlocking { newExporter(r).recover(pre) }

        assertTrue(result === RecoveryResult.Abandoned)
        // No row pointer → no delete attempt; no validate attempt either.
        assertEquals(emptyList<String>(), r.events)

        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
        assertNull(m.pendingUri)
        assertEquals(1, store.setExportFailedCalls)
    }

    // ─── Valid pending row ───────────────────────────────────────────

    @Test
    fun `valid pending row - finalize Resumed Success pendingUri retained`() {
        val r = Recorder()  // validate=true, finalize=true defaults
        val pre = tier1Manifest()
        writePreState(pre)

        val result = runBlocking { newExporter(r).recover(pre) }

        assertTrue("expected Resumed, got $result", result is RecoveryResult.Resumed)
        val export = (result as RecoveryResult.Resumed).export
        assertTrue("expected Success, got $export", export is ExportResult.Success)
        val success = export as ExportResult.Success
        assertTrue(success.mediaScanCompleted)
        assertFalse(success.privateTempRetained)

        // Sequence: validate, finalize. No delete (success path).
        assertEquals(listOf("validate($testUri)", "finalize($testUri)"), r.events)

        val m = reload()
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertEquals("pendingUri retained on Tier 1 recovery success", testUri, m.pendingUri)
    }

    // ─── Corrupt / invalid pending row ──────────────────────────────

    @Test
    fun `validate returns false - delete row abandon FAILED`() {
        val r = Recorder().apply { validateReturn = false }
        val pre = tier1Manifest()
        writePreState(pre)

        val result = runBlocking { newExporter(r).recover(pre) }

        assertTrue(result === RecoveryResult.Abandoned)
        // Validate attempted, then delete (cleanup), then setExportFailed.
        // No finalize.
        assertEquals(listOf("validate($testUri)", "delete($testUri)"), r.events)

        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
        assertNull("FAILED clears pendingUri", m.pendingUri)
        assertEquals(1, store.setExportFailedCalls)
    }

    @Test
    fun `validate throws - treated as invalid - delete row abandon FAILED`() {
        val r = Recorder().apply { validateThrow = IOException("extractor blew up") }
        val pre = tier1Manifest()
        writePreState(pre)

        val result = runBlocking { newExporter(r).recover(pre) }

        assertTrue(result === RecoveryResult.Abandoned)
        assertTrue(r.events.contains("validate($testUri)"))
        assertTrue(r.events.contains("delete($testUri)"))
        assertFalse(r.events.any { it.startsWith("finalize") })
    }

    // ─── Validate ok + finalize anomalies (Blocker 1 NO-GO patch) ───
    //
    // After validatePending=true, recovery MUST NOT delete the row.
    // The crash sequence the patch defends against:
    //   1. Live or prior recovery flips IS_PENDING=0 successfully
    //      (artifact published in gallery).
    //   2. Process killed before setExportFinalized writes FINALIZED.
    //   3. Next cold launch sees manifest pendingUri + non-FINALIZED
    //      state.
    //   4. validatePending succeeds (artifact intact).
    //   5. finalizePendingRow.update() returns 0 rows because
    //      IS_PENDING is already 0 (or throws on a transient error).
    // Old behavior would have `abandon → delete` here, destroying the
    // user's video. New behavior: trust the artifact, write FINALIZED.

    @Test
    fun `validate ok and finalize NoRowsUpdated - lost FINALIZED write recovery, row retained, manifest FINALIZED`() {
        val r = Recorder().apply { finalizeResult = Tier1FinalizeResult.NoRowsUpdated }
        val pre = tier1Manifest()
        writePreState(pre)

        val result = runBlocking { newExporter(r).recover(pre) }

        assertTrue("expected Resumed(Success), got $result", result is RecoveryResult.Resumed)
        val export = (result as RecoveryResult.Resumed).export
        assertTrue(export is ExportResult.Success)

        // No delete — trusting the artifact.
        assertFalse(
            "row MUST NOT be deleted after validate=true; deleting would destroy user data " +
                "if a prior run already committed IS_PENDING=0 but lost the manifest write",
            r.events.any { it.startsWith("delete") }
        )
        assertEquals(listOf("validate($testUri)", "finalize($testUri)"), r.events)

        val m = reload()
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertEquals("pendingUri retained", testUri, m.pendingUri)
    }

    @Test
    fun `validate ok and finalize Failed - row retained RetryableFailure deferred to next launch`() {
        val cause = IOException("transient MediaStore error")
        val r = Recorder().apply { finalizeResult = Tier1FinalizeResult.Failed(cause) }
        val pre = tier1Manifest()
        writePreState(pre)

        val result = runBlocking { newExporter(r).recover(pre) }

        assertTrue(
            "expected RetryableFailure, got $result",
            result is RecoveryResult.RetryableFailure
        )
        val rf = result as RecoveryResult.RetryableFailure
        assertEquals("finalizePendingRow", rf.phase)
        assertEquals(cause, rf.cause)

        // No delete — artifact intact per validatePending; defer.
        assertFalse(
            "row MUST NOT be deleted after validate=true",
            r.events.any { it.startsWith("delete") }
        )
        // Manifest unchanged — recover did NOT write FAILED nor FINALIZED.
        val m = reload()
        assertEquals(
            "manifest left in pre-recovery state for next-launch retry",
            ExportState.MUXING, m.exportState
        )
        assertEquals(testUri, m.pendingUri)
        assertEquals(0, store.setExportFailedCalls)
        assertEquals(0, store.setExportFinalizedCalls)
    }

    // ─── Cleanup-write contract under abandon path ──────────────────

    @Test
    fun `validate false and setExportFailed Failed propagates ManifestWriteFailed`() {
        val cause = IOException("manifest fsync exhausted")
        store.setExportFailedOverride = ExportMutationResult.Failed(cause, attempts = 3)
        val r = Recorder().apply { validateReturn = false }
        val pre = tier1Manifest()
        writePreState(pre)

        val result = runBlocking { newExporter(r).recover(pre) }

        assertTrue(
            "expected ManifestWriteFailed, got $result",
            result is RecoveryResult.ManifestWriteFailed
        )
        assertEquals(cause, (result as RecoveryResult.ManifestWriteFailed).cause)
    }

    @Test
    fun `validate false and setExportFailed UnknownSession propagates UnknownSession`() {
        store.setExportFailedOverride = ExportMutationResult.UnknownSession(sessionId)
        val r = Recorder().apply { validateReturn = false }
        val pre = tier1Manifest()
        writePreState(pre)

        val result = runBlocking { newExporter(r).recover(pre) }

        assertTrue(result is RecoveryResult.UnknownSession)
        assertEquals(sessionId, (result as RecoveryResult.UnknownSession).sessionId)
    }

    // ─── setExportFinalized Failed at the end ───────────────────────

    @Test
    fun `valid pending and setExportFinalized Failed propagates ManifestWriteFailed`() {
        val cause = IOException("final write exhausted")
        store.setExportFinalizedOverride = ExportMutationResult.Failed(cause, attempts = 3)
        val r = Recorder()
        val pre = tier1Manifest()
        writePreState(pre)

        val result = runBlocking { newExporter(r).recover(pre) }

        assertTrue(
            "expected ManifestWriteFailed, got $result",
            result is RecoveryResult.ManifestWriteFailed
        )
        assertEquals(cause, (result as RecoveryResult.ManifestWriteFailed).cause)
        // Finalize ran (returned true) but manifest write failed; row
        // is NOT deleted because IS_PENDING=0 already published the
        // artifact to the user.
        assertFalse("row must NOT be deleted after successful finalize", r.events.any { it.startsWith("delete") })
    }

    // ─── Tier guard ─────────────────────────────────────────────────

    @Test
    fun `Tier1Exporter rejects non-Tier1 manifest in recover()`() {
        val nonTier1 = initial.copy(exportTier = ExportTier.TIER3_API24_25)
        val r = Recorder()
        try {
            runBlocking { newExporter(r).recover(nonTier1) }
            fail("recover should reject non-Tier 1 manifest")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ─── Phase 6.1b T14 — per-side recovery (side != null) ──────────

    /**
     * Phase 6.1b T14 — per-side Tier 1 recovery happy path. `recover(m,
     * side = PORTRAIT)` reads `m.portraitPendingUri` (NOT the shared
     * `pendingUri`), validates it, finalizes, then writes
     * `setExportFinalizedForSide(PORTRAIT, ...)` and NOT shared
     * `setExportFinalized`. The shared `exportState` stays untouched —
     * T13's caller owns the final shared write after both sides settle.
     */
    @Test
    fun `recover with side PORTRAIT reads portrait pendingUri and writes per-side finalized`() {
        val r = Recorder().apply {
            validateReturn = true
            finalizeResult = Tier1FinalizeResult.Finalized
        }
        val portraitUri = "content://media/external/video/media/PORTRAIT_77"
        // P+L manifest: portrait pending populated; shared pendingUri null.
        val pre = initial.copy(
            exportTier = ExportTier.TIER1_API29_PLUS,
            pendingUri = null,
            portraitPendingUri = portraitUri,
            exportState = ExportState.MUXING
        )
        writePreState(pre)

        val result = runBlocking {
            newExporter(r).recover(
                pre,
                side = com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT
            )
        }

        assertTrue("expected Resumed(Success), got $result", result is RecoveryResult.Resumed)
        val export = (result as RecoveryResult.Resumed).export
        assertTrue("expected ExportResult.Success, got $export", export is ExportResult.Success)
        // Validate + finalize seams ran against the portrait URI.
        assertTrue(
            "validate(portrait) must be invoked, events=${r.events}",
            r.events.contains("validate($portraitUri)")
        )
        assertTrue(
            "finalize(portrait) must be invoked, events=${r.events}",
            r.events.contains("finalize($portraitUri)")
        )
        // Per-side mutator called; shared NOT called.
        assertEquals(1, store.setExportFinalizedForSideCalls)
        assertEquals(
            com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT,
            store.lastSetExportFinalizedForSide
        )
        assertEquals(0, store.setExportFinalizedCalls)
        // Manifest reflects per-side write; shared exportState untouched
        // (stays MUXING — T13's caller advances it).
        val m = reload()
        assertEquals(ExportState.MUXING, m.exportState)
        assertEquals(portraitUri, m.portraitPublicTargetPath)
        // Landscape untouched.
        assertNull(m.landscapePublicTargetPath)
    }

    @Test
    fun `recover with side LANDSCAPE reads landscape pendingUri and writes per-side finalized`() {
        val r = Recorder().apply {
            validateReturn = true
            finalizeResult = Tier1FinalizeResult.Finalized
        }
        val landscapeUri = "content://media/external/video/media/LANDSCAPE_88"
        val pre = initial.copy(
            exportTier = ExportTier.TIER1_API29_PLUS,
            pendingUri = null,
            landscapePendingUri = landscapeUri,
            exportState = ExportState.MUXING
        )
        writePreState(pre)

        val result = runBlocking {
            newExporter(r).recover(
                pre,
                side = com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE
            )
        }

        assertTrue("expected Resumed(Success), got $result", result is RecoveryResult.Resumed)
        assertTrue(r.events.contains("validate($landscapeUri)"))
        assertTrue(r.events.contains("finalize($landscapeUri)"))
        assertEquals(1, store.setExportFinalizedForSideCalls)
        assertEquals(
            com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE,
            store.lastSetExportFinalizedForSide
        )
        assertEquals(0, store.setExportFinalizedCalls)

        val m = reload()
        assertEquals(landscapeUri, m.landscapePublicTargetPath)
        assertNull(m.portraitPublicTargetPath)
    }

    /**
     * Phase 6.1b T14 — null per-side pendingUri abandons WITHOUT flipping
     * shared `exportState` to FAILED (OQ-C lock: T13 owns failure
     * attribution). The per-side row deletion still runs (none here —
     * pointer is null).
     */
    @Test
    fun `recover with side PORTRAIT and null portrait pendingUri abandons without shared setExportFailed`() {
        val r = Recorder()
        val pre = initial.copy(
            exportTier = ExportTier.TIER1_API29_PLUS,
            pendingUri = null,
            portraitPendingUri = null,
            exportState = ExportState.MUXING
        )
        writePreState(pre)

        val result = runBlocking {
            newExporter(r).recover(
                pre,
                side = com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT
            )
        }

        assertTrue("expected Abandoned, got $result", result === RecoveryResult.Abandoned)
        // Shared setExportFailed must NOT be called on the per-side path.
        assertEquals(0, store.setExportFailedCalls)
        // Shared exportState stays MUXING — T13 owns the final write.
        val m = reload()
        assertEquals(ExportState.MUXING, m.exportState)
    }

    /**
     * Phase 6.1b T14 — single-mode recover (side = null) preserves the
     * pre-T14 byte-identical behavior. The shared `setExportFinalized`
     * is called; per-side mutators are NOT touched.
     */
    @Test
    fun `recover with side null preserves single-mode shared setExportFinalized`() {
        val r = Recorder().apply { validateReturn = true; finalizeResult = Tier1FinalizeResult.Finalized }
        val pre = tier1Manifest()
        writePreState(pre)

        val result = runBlocking { newExporter(r).recover(pre) }

        assertTrue(result is RecoveryResult.Resumed)
        assertEquals(1, store.setExportFinalizedCalls)
        assertEquals(0, store.setExportFinalizedForSideCalls)
    }
}
