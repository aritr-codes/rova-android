package com.aritr.rova.service.export

import com.aritr.rova.data.ExportMutationResult
import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 1.7 commit-2 — Tier 3 cold-launch recovery routine, ADR 0003
 * §"Recovery routing" Cases A/B/C/D.
 *
 * Each case is exercised via a hand-crafted manifest pre-state +
 * matching disk fixture. The recovery routine MUST:
 * - Case A — `publicTargetFile` exists with content: re-run scan, then
 *   finalize. Result: exportState=FINALIZED, mediaScanCompleted reflects
 *   scan-callback outcome, privateTempPath cleared on the happy
 *   sub-case.
 * - Case B — only `<name>.mp4.part` exists: delete the .part, fall
 *   through. If `privateTempFile` is readable → resume publish-and-
 *   finalize (Case C-equivalent action). If unreadable → abandon.
 * - Case C — only `privateTempFile` exists: resume publish-and-
 *   finalize.
 * - Case D — nothing usable: setExportFailed; manifest pointers
 *   cleared.
 */
class Tier3RecoveryTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var store: SessionStore
    private lateinit var sessionId: String
    private lateinit var initial: SessionManifest

    private lateinit var privateTempFile: File
    private lateinit var publicTargetFile: File
    private val partFile: File get() = File(publicTargetFile.parentFile, "${publicTargetFile.name}.part")

    @Before
    fun setUp() {
        store = SessionStore(tmp.newFolder("videos"))
        initial = store.createSession(
            config = SessionConfig(
                durationSeconds = 30,
                intervalMinutes = 5,
                resolution = "FHD",
                loopCount = 4
            )
        )
        sessionId = initial.sessionId
        privateTempFile = File(store.sessionDir(sessionId), "export/${sessionId}.mp4")
        // Ensure parent exists for tests that pre-create files.
        publicTargetFile = File(tmp.newFolder("public_movies_rova"), "Rova_$sessionId.mp4")
    }

    @After
    fun tearDown() {
        store.close()
    }

    /** Bypass mutators to inject the recovery pre-state. */
    private fun writePreState(updated: SessionManifest) {
        val target = File(store.sessionDir(updated.sessionId), "manifest.json")
        target.writeText(updated.toJson().toString(2))
    }

    private fun newExporter(
        scanResult: Boolean = true,
        mux: suspend (List<File>, File) -> Unit = { _, output ->
            output.parentFile?.mkdirs()
            output.writeBytes("MOCK".toByteArray())
        }
    ) = Tier3Exporter(
        sessionStore = store,
        mediaScanWaiter = FakeMediaScanWaiter(scanResult),
        mux = mux
    )

    private fun reload(): SessionManifest =
        requireNotNull(store.loadManifest(sessionId)) { "manifest disappeared" }

    private fun tier3Manifest(
        privateTempPath: String? = privateTempFile.absolutePath,
        publicTargetPath: String? = publicTargetFile.absolutePath,
        exportState: ExportState = ExportState.MUXING,
        mediaScanCompleted: Boolean = false
    ): SessionManifest = initial.copy(
        exportTier = ExportTier.TIER3_API24_25,
        privateTempPath = privateTempPath,
        publicTargetPath = publicTargetPath,
        mediaScanCompleted = mediaScanCompleted,
        exportState = exportState
    )

    // ─── Case A — public target exists, scan callback fires ─────────

    @Test
    fun `case A - publicTargetFile exists and scan succeeds - finalize cleanly`() {
        publicTargetFile.parentFile?.mkdirs()
        publicTargetFile.writeBytes("ALREADY_PUBLISHED".toByteArray())
        privateTempFile.parentFile?.mkdirs()
        privateTempFile.writeBytes("STILL_HERE".toByteArray())
        val pre = tier3Manifest(exportState = ExportState.FINALIZED, mediaScanCompleted = false)
        writePreState(pre)

        val result = runBlocking { newExporter(scanResult = true).recover(pre) }

        assertTrue("expected Resumed, got $result", result is RecoveryResult.Resumed)
        val export = (result as RecoveryResult.Resumed).export
        assertTrue(export is ExportResult.Success)
        val success = export as ExportResult.Success
        assertTrue(success.mediaScanCompleted)
        assertFalse(success.privateTempRetained)

        assertTrue("public artifact left in place", publicTargetFile.exists())
        assertFalse("private temp deleted on Case A happy", privateTempFile.exists())

        val m = reload()
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertTrue(m.mediaScanCompleted)
        assertNull(m.privateTempPath)
        assertEquals(publicTargetFile.absolutePath, m.publicTargetPath)
    }

    @Test
    fun `case A - publicTargetFile exists but scan times out - retain private temp`() {
        publicTargetFile.parentFile?.mkdirs()
        publicTargetFile.writeBytes("ALREADY_PUBLISHED".toByteArray())
        privateTempFile.parentFile?.mkdirs()
        privateTempFile.writeBytes("STILL_HERE".toByteArray())
        val pre = tier3Manifest(exportState = ExportState.FINALIZED, mediaScanCompleted = false)
        writePreState(pre)

        val result = runBlocking { newExporter(scanResult = false).recover(pre) }

        assertTrue(result is RecoveryResult.Resumed)
        val success = (result as RecoveryResult.Resumed).export as ExportResult.Success
        assertFalse(success.mediaScanCompleted)
        assertTrue(success.privateTempRetained)

        assertTrue(publicTargetFile.exists())
        assertTrue("private temp retained on scan timeout", privateTempFile.exists())

        val m = reload()
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertFalse(m.mediaScanCompleted)
        assertEquals(privateTempFile.absolutePath, m.privateTempPath)
    }

    // ─── Case B — .part exists, public target missing ───────────────

    @Test
    fun `case B - part exists with valid private temp - resume from copy step`() {
        partFile.parentFile?.mkdirs()
        partFile.writeBytes("HALF_COPIED".toByteArray())
        privateTempFile.parentFile?.mkdirs()
        privateTempFile.writeBytes("VALID_MERGED_OUTPUT".toByteArray())
        val pre = tier3Manifest(exportState = ExportState.COPYING)
        writePreState(pre)

        val result = runBlocking { newExporter(scanResult = true).recover(pre) }

        assertTrue("expected Resumed export, got $result", result is RecoveryResult.Resumed)
        val export = (result as RecoveryResult.Resumed).export
        assertTrue(export is ExportResult.Success)
        val success = export as ExportResult.Success
        assertTrue(success.mediaScanCompleted)

        // Stale .part deleted; public artifact published from privateTemp.
        assertFalse(".part cleaned up", partFile.exists())
        assertTrue(publicTargetFile.exists())
        assertFalse(privateTempFile.exists())

        val m = reload()
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertNull(m.privateTempPath)
    }

    @Test
    fun `case B - part exists but private temp is missing - abandon`() {
        partFile.parentFile?.mkdirs()
        partFile.writeBytes("ORPHAN_PART".toByteArray())
        // No privateTempFile on disk.
        val pre = tier3Manifest(exportState = ExportState.COPYING)
        writePreState(pre)

        val result = runBlocking { newExporter().recover(pre) }

        assertTrue("expected Abandoned, got $result", result === RecoveryResult.Abandoned)
        assertFalse("orphan .part cleaned up", partFile.exists())
        assertFalse(publicTargetFile.exists())

        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
        assertNull(m.privateTempPath)
        assertNull(m.publicTargetPath)
    }

    // ─── Case C — only privateTempFile exists ───────────────────────

    @Test
    fun `case C - only privateTempFile exists and is valid - resume from copy step`() {
        privateTempFile.parentFile?.mkdirs()
        privateTempFile.writeBytes("MUX_DONE_COPY_NEVER_STARTED".toByteArray())
        val pre = tier3Manifest(exportState = ExportState.MUXING)
        writePreState(pre)

        val result = runBlocking { newExporter(scanResult = true).recover(pre) }

        assertTrue(result is RecoveryResult.Resumed)
        val success = (result as RecoveryResult.Resumed).export as ExportResult.Success
        assertTrue(success.mediaScanCompleted)

        assertTrue(publicTargetFile.exists())
        assertFalse(privateTempFile.exists())

        val m = reload()
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertNull(m.privateTempPath)
        assertEquals(publicTargetFile.absolutePath, m.publicTargetPath)
    }

    // ─── Case D — nothing usable ────────────────────────────────────

    @Test
    fun `case D - no public, no part, no private temp - abandon`() {
        val pre = tier3Manifest(exportState = ExportState.MUXING)
        writePreState(pre)

        val result = runBlocking { newExporter().recover(pre) }

        assertTrue("expected Abandoned, got $result", result === RecoveryResult.Abandoned)

        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
        assertNull(m.privateTempPath)
        assertNull(m.publicTargetPath)
        assertNull(m.pendingUri)
        assertFalse(m.mediaScanCompleted)
    }

    @Test
    fun `case D - empty privateTempFile (zero bytes) is treated as unrecoverable`() {
        privateTempFile.parentFile?.mkdirs()
        privateTempFile.createNewFile()  // zero-length
        val pre = tier3Manifest(exportState = ExportState.MUXING)
        writePreState(pre)

        val result = runBlocking { newExporter().recover(pre) }

        assertTrue(result === RecoveryResult.Abandoned)
        assertFalse(publicTargetFile.exists())

        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
        assertNull(m.privateTempPath)
    }

    // ─── Manifest pointer null / missing ────────────────────────────

    @Test
    fun `recover with null pointers - abandons (inconsistent manifest)`() {
        // exportState is MUXING but neither pointer is set — this is
        // an inconsistent manifest the routing layer wouldn't normally
        // produce, but recover() must still clean up gracefully.
        val pre = tier3Manifest(
            privateTempPath = null,
            publicTargetPath = null,
            exportState = ExportState.MUXING
        )
        writePreState(pre)

        val result = runBlocking { newExporter().recover(pre) }

        assertTrue(result === RecoveryResult.Abandoned)
        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
    }

    @Test
    fun `recover preserves session id sanity in returned result`() {
        val pre = tier3Manifest(exportState = ExportState.MUXING)
        writePreState(pre)
        val result = runBlocking { newExporter().recover(pre) }
        assertNotNull(result)
    }

    // ─── Result contract under cleanup-write failures (no swallowing) ───

    /**
     * Case D recovery (nothing usable on disk) calls
     * `cleanupOnFailure` → `setExportFailed`. If the mutation returns
     * `Failed` the recovery must propagate
     * `RecoveryResult.ManifestWriteFailed` — emitting `Abandoned`
     * would tell the cleanup pass the session is fully retired when
     * the manifest is actually still in `MUXING`/`COPYING`/`FINALIZED`.
     */
    @Test
    fun `case D with setExportFailed returning Failed propagates ManifestWriteFailed`() {
        val store = StubbingSessionStore(tmp.newFolder("videos2"))
        val initialManifest = store.createSession(
            config = SessionConfig(
                durationSeconds = 30,
                intervalMinutes = 5,
                resolution = "FHD",
                loopCount = 4
            )
        )
        val sid = initialManifest.sessionId
        val priv = File(store.sessionDir(sid), "export/$sid.mp4")
        val pub = File(tmp.newFolder("public2"), "Rova_$sid.mp4")
        val pre = initialManifest.copy(
            exportTier = ExportTier.TIER3_API24_25,
            privateTempPath = priv.absolutePath,
            publicTargetPath = pub.absolutePath,
            exportState = ExportState.MUXING
        )
        File(store.sessionDir(sid), "manifest.json")
            .writeText(pre.toJson().toString(2))

        val cause = java.io.IOException("manifest fsync exhausted in recovery")
        store.setExportFailedOverride = ExportMutationResult.Failed(cause, attempts = 3)

        val exporter = Tier3Exporter(
            sessionStore = store,
            mediaScanWaiter = FakeMediaScanWaiter(scanResult = true),
            mux = { _, _ -> }
        )

        val result = runBlocking { exporter.recover(pre) }

        assertTrue(
            "expected ManifestWriteFailed not Abandoned, got $result",
            result is RecoveryResult.ManifestWriteFailed
        )
        assertEquals(cause, (result as RecoveryResult.ManifestWriteFailed).cause)
        assertEquals(1, store.setExportFailedCalls)

        store.close()
    }

    /**
     * If the manifest disappears between recovery's read and the
     * `setExportFailed` write (concurrent cleanup), recovery must
     * propagate `RecoveryResult.UnknownSession` instead of
     * `Abandoned`.
     */
    @Test
    fun `case D with setExportFailed returning UnknownSession propagates UnknownSession`() {
        val store = StubbingSessionStore(tmp.newFolder("videos3"))
        val initialManifest = store.createSession(
            config = SessionConfig(
                durationSeconds = 30,
                intervalMinutes = 5,
                resolution = "FHD",
                loopCount = 4
            )
        )
        val sid = initialManifest.sessionId
        val priv = File(store.sessionDir(sid), "export/$sid.mp4")
        val pub = File(tmp.newFolder("public3"), "Rova_$sid.mp4")
        val pre = initialManifest.copy(
            exportTier = ExportTier.TIER3_API24_25,
            privateTempPath = priv.absolutePath,
            publicTargetPath = pub.absolutePath,
            exportState = ExportState.MUXING
        )
        File(store.sessionDir(sid), "manifest.json")
            .writeText(pre.toJson().toString(2))

        store.setExportFailedOverride = ExportMutationResult.UnknownSession(sid)

        val exporter = Tier3Exporter(
            sessionStore = store,
            mediaScanWaiter = FakeMediaScanWaiter(scanResult = true),
            mux = { _, _ -> }
        )

        val result = runBlocking { exporter.recover(pre) }

        assertTrue(
            "expected UnknownSession not Abandoned, got $result",
            result is RecoveryResult.UnknownSession
        )
        assertEquals(sid, (result as RecoveryResult.UnknownSession).sessionId)
        assertEquals(1, store.setExportFailedCalls)

        store.close()
    }

    // ─── Phase 6.1b T14 — per-side recovery (side != null) ──────────

    /**
     * Phase 6.1b T14 — per-side Tier 3 recovery, Case A (public artifact
     * already on disk). `recover(m, side = PORTRAIT)` reads
     * `m.portraitPrivateTempPath` + `m.portraitPublicTargetPath`. Per-side
     * mutators are used (`setExportFinalizedForSide` +
     * `setMediaScanCompletedForSide`); shared `setExportFinalized` is
     * NOT called — shared `exportState` stays untouched per the OQ-C
     * lock (T13's caller owns the final shared advance).
     */
    @Test
    fun `recover side PORTRAIT case A reads portrait pointers and writes per-side mutators`() {
        // Distinct portrait artifact paths, not the shared ones.
        val portraitPriv = File(store.sessionDir(sessionId), "export/${sessionId}_P.mp4")
        val portraitPub = File(publicTargetFile.parentFile, "Rova_${sessionId}_P.mp4")
        portraitPub.parentFile?.mkdirs()
        portraitPub.writeBytes("ALREADY_PUBLISHED_PORTRAIT".toByteArray())
        portraitPriv.parentFile?.mkdirs()
        portraitPriv.writeBytes("STILL_HERE_P".toByteArray())

        val pre = initial.copy(
            exportTier = ExportTier.TIER3_API24_25,
            // Shared single-mode pointers null — P+L manifest.
            privateTempPath = null,
            publicTargetPath = null,
            portraitPrivateTempPath = portraitPriv.absolutePath,
            portraitPublicTargetPath = portraitPub.absolutePath,
            exportState = ExportState.FINALIZED
        )
        writePreState(pre)

        val result = runBlocking {
            newExporter(scanResult = true).recover(
                pre,
                side = com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT
            )
        }

        assertTrue("expected Resumed, got $result", result is RecoveryResult.Resumed)
        val export = (result as RecoveryResult.Resumed).export
        assertTrue(export is ExportResult.Success)

        assertTrue("public portrait artifact left in place", portraitPub.exists())
        assertFalse("portrait private temp deleted on case-A happy", portraitPriv.exists())

        val m = reload()
        // Shared state untouched — T13 owns final advance.
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertNull("shared privateTempPath must not be written", m.privateTempPath)
        assertNull("shared publicTargetPath must not be written", m.publicTargetPath)
        assertFalse("shared mediaScanCompleted must not be flipped", m.mediaScanCompleted)
        // Per-side fields updated.
        assertEquals(portraitPub.absolutePath, m.portraitPublicTargetPath)
        assertNull("portrait private temp cleared on happy path", m.portraitPrivateTempPath)
        assertTrue("portrait scan completed", m.portraitMediaScanCompleted)
        // Landscape untouched.
        assertNull(m.landscapePublicTargetPath)
        assertFalse(m.landscapeMediaScanCompleted)
    }

    /**
     * Phase 6.1b T14 — per-side Tier 3 recovery, Case C (only private
     * temp on disk → resume publish-and-finalize). LANDSCAPE side.
     */
    @Test
    fun `recover side LANDSCAPE case C resumes publish-and-finalize from landscape private temp`() {
        val landscapePriv = File(store.sessionDir(sessionId), "export/${sessionId}_L.mp4")
        val landscapePub = File(publicTargetFile.parentFile, "Rova_${sessionId}_L.mp4")
        landscapePriv.parentFile?.mkdirs()
        landscapePriv.writeBytes("MUX_DONE_LANDSCAPE".toByteArray())

        val pre = initial.copy(
            exportTier = ExportTier.TIER3_API24_25,
            privateTempPath = null,
            publicTargetPath = null,
            landscapePrivateTempPath = landscapePriv.absolutePath,
            landscapePublicTargetPath = landscapePub.absolutePath,
            exportState = ExportState.MUXING
        )
        writePreState(pre)

        val result = runBlocking {
            newExporter(scanResult = true).recover(
                pre,
                side = com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE
            )
        }

        assertTrue("expected Resumed, got $result", result is RecoveryResult.Resumed)
        assertTrue(landscapePub.exists())
        assertFalse(landscapePriv.exists())

        val m = reload()
        // Shared exportState untouched (stays MUXING — T13 advances).
        assertEquals(ExportState.MUXING, m.exportState)
        assertEquals(landscapePub.absolutePath, m.landscapePublicTargetPath)
        assertNull(m.landscapePrivateTempPath)
        assertTrue(m.landscapeMediaScanCompleted)
        // Portrait untouched.
        assertNull(m.portraitPublicTargetPath)
    }

    /**
     * Phase 6.1b T14 — per-side recovery Case D (nothing usable on
     * portrait side) skips shared `setExportFailed` (OQ-C lock: T13
     * owns shared-FAILED attribution after both sides settle). Returns
     * Abandoned so the runner records the per-side outcome.
     */
    @Test
    fun `recover side PORTRAIT case D skips shared setExportFailed write`() {
        val store2 = StubbingSessionStore(tmp.newFolder("videos_t14"))
        val initialManifest = store2.createSession(
            config = SessionConfig(
                durationSeconds = 30,
                intervalMinutes = 5,
                resolution = "FHD",
                loopCount = 4
            )
        )
        val sid = initialManifest.sessionId
        val portraitPriv = File(store2.sessionDir(sid), "export/${sid}_P.mp4")
        val portraitPub = File(tmp.newFolder("public_t14"), "Rova_${sid}_P.mp4")
        val pre = initialManifest.copy(
            exportTier = ExportTier.TIER3_API24_25,
            // Nothing on disk — Case D.
            portraitPrivateTempPath = portraitPriv.absolutePath,
            portraitPublicTargetPath = portraitPub.absolutePath,
            exportState = ExportState.MUXING
        )
        File(store2.sessionDir(sid), "manifest.json").writeText(pre.toJson().toString(2))

        val exporter = Tier3Exporter(
            sessionStore = store2,
            mediaScanWaiter = FakeMediaScanWaiter(scanResult = true),
            mux = { _, _ -> }
        )

        val result = runBlocking {
            exporter.recover(
                pre,
                side = com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT
            )
        }

        assertTrue("expected Abandoned, got $result", result === RecoveryResult.Abandoned)
        // Shared setExportFailed MUST NOT be called on per-side path.
        assertEquals(
            "shared setExportFailed must not be called on per-side recovery",
            0,
            store2.setExportFailedCalls
        )

        store2.close()
    }

    /**
     * Phase 6.1b T14 — single-mode recover (side = null) preserves the
     * pre-T14 byte-identical behavior end-to-end (Case A happy path).
     * This guard ensures the new default parameter does not change the
     * single-mode write path.
     */
    @Test
    fun `recover side null preserves shared single-mode write contract case A`() {
        publicTargetFile.parentFile?.mkdirs()
        publicTargetFile.writeBytes("PUBLISHED".toByteArray())
        privateTempFile.parentFile?.mkdirs()
        privateTempFile.writeBytes("STILL_HERE".toByteArray())
        val pre = tier3Manifest(exportState = ExportState.FINALIZED, mediaScanCompleted = false)
        writePreState(pre)

        val result = runBlocking { newExporter(scanResult = true).recover(pre) }

        assertTrue(result is RecoveryResult.Resumed)
        val m = reload()
        // Shared mutators wrote: shared exportState FINALIZED + shared
        // publicTargetPath + mediaScanCompleted.
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertEquals(publicTargetFile.absolutePath, m.publicTargetPath)
        assertTrue(m.mediaScanCompleted)
        assertNull(m.privateTempPath)
        // Per-side fields stay defaults.
        assertNull(m.portraitPublicTargetPath)
        assertNull(m.landscapePublicTargetPath)
    }
}
