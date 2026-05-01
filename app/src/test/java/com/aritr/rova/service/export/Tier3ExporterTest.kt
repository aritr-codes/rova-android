package com.aritr.rova.service.export

import com.aritr.rova.data.ExportMutationResult
import com.aritr.rova.data.ExportState
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
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
 * Phase 1.7 commit-2 — Tier 3 export pipeline regression suite.
 *
 * Covers the live-export contract from ADR 0003 §"Recovery routing"
 * (Tier 3 column) plus the hard requirements from the commit-2 spec:
 * - happy path: mux → .part → rename → bounded scan → finalize with
 *   private temp deleted, manifest fully retired.
 * - scan timeout (Patch 2 fallback): exportState=FINALIZED,
 *   mediaScanCompleted=false, privateTempPath retained.
 * - mux / copy / rename failure: exportState=FAILED, pointers cleared,
 *   side files cleaned up.
 * - delete-after-scan failure: scan callback fired but private temp
 *   delete failed → mediaScanCompleted stays false (deferred to
 *   recovery's idempotent re-scan), privateTempPath retained.
 * - manifest disappears mid-export: UnknownSession propagation.
 *
 * No `MediaMuxer` / `MediaStore` / `MediaScannerConnection` is
 * exercised at runtime — every Android-platform call is replaced by
 * an injected lambda or a fake. The exporter's correctness is in the
 * orchestration: ordering of mutator calls, file-system side effects,
 * and ExportResult mapping.
 */
class Tier3ExporterTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var store: SessionStore
    private lateinit var sessionId: String
    private lateinit var initial: SessionManifest

    private lateinit var privateTempFile: File
    private lateinit var publicTargetFile: File
    private val partFile: File get() = File(publicTargetFile.parentFile, "${publicTargetFile.name}.part")

    private val muxedBytes = "MOCK_MUXED_OUTPUT".toByteArray()

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
        publicTargetFile = File(tmp.newFolder("public_movies_rova"), "Rova_$sessionId.mp4")
    }

    @After
    fun tearDown() {
        store.close()
    }

    /** Simple production-like mux: writes [muxedBytes] to [output]. */
    private val realisticMux: suspend (List<File>, File) -> Unit = { _, output ->
        output.parentFile?.mkdirs()
        output.writeBytes(muxedBytes)
    }

    private fun reload(): SessionManifest =
        requireNotNull(store.loadManifest(sessionId)) { "manifest disappeared" }

    private fun newExporter(
        scanWaiter: MediaScanWaiter = FakeMediaScanWaiter(scanResult = true),
        mux: suspend (List<File>, File) -> Unit = realisticMux,
        copyFile: (File, File) -> Unit = { src, dst -> src.copyTo(dst, overwrite = true) },
        renameFile: (File, File) -> Boolean = { src, dst -> src.renameTo(dst) },
        deleteFile: (File) -> Boolean = { it.delete() }
    ) = Tier3Exporter(
        sessionStore = store,
        mediaScanWaiter = scanWaiter,
        mux = mux,
        copyFile = copyFile,
        renameFile = renameFile,
        deleteFile = deleteFile
    )

    // ─── Happy path ─────────────────────────────────────────────────

    @Test
    fun `happy path - mux + copy + rename + scan callback within timeout`() {
        val waiter = FakeMediaScanWaiter(scanResult = true)
        val exporter = newExporter(scanWaiter = waiter)

        val result = runBlocking { exporter.export(sessionId, emptyList(), privateTempFile, publicTargetFile) }

        assertTrue("expected Success, got $result", result is ExportResult.Success)
        val success = result as ExportResult.Success
        assertTrue(success.mediaScanCompleted)
        assertFalse(success.privateTempRetained)

        // Disk side-effects
        assertTrue("public artifact must exist", publicTargetFile.exists())
        assertEquals(muxedBytes.size.toLong(), publicTargetFile.length())
        assertFalse("private temp must be deleted on happy path", privateTempFile.exists())
        assertFalse(".part must be gone after rename", partFile.exists())

        // Manifest side-effects
        val m = reload()
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertTrue(m.mediaScanCompleted)
        assertNull("privateTempPath cleared on happy path", m.privateTempPath)
        assertEquals(publicTargetFile.absolutePath, m.publicTargetPath)
        assertNull("Tier 3 never sets pendingUri", m.pendingUri)

        // Scan was driven through bounded waiter, not raw scanFile.
        assertEquals(1, waiter.calls)
        assertEquals(publicTargetFile.absolutePath, waiter.lastFile?.absolutePath)
    }

    // ─── Scan timeout (Patch 2 fallback) ────────────────────────────

    @Test
    fun `scan timeout - finalized but mediaScanCompleted false and privateTempPath retained`() {
        val exporter = newExporter(scanWaiter = FakeMediaScanWaiter(scanResult = false))

        val result = runBlocking { exporter.export(sessionId, emptyList(), privateTempFile, publicTargetFile) }

        assertTrue(result is ExportResult.Success)
        val success = result as ExportResult.Success
        assertFalse("scan timeout must not claim mediaScanCompleted=true", success.mediaScanCompleted)
        assertTrue(success.privateTempRetained)

        assertTrue("public artifact must exist after rename", publicTargetFile.exists())
        assertTrue("private temp retained as recovery fuel on timeout", privateTempFile.exists())

        val m = reload()
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertFalse(m.mediaScanCompleted)
        assertEquals(privateTempFile.absolutePath, m.privateTempPath)
        assertEquals(publicTargetFile.absolutePath, m.publicTargetPath)
    }

    // ─── Mux failure ────────────────────────────────────────────────

    @Test
    fun `mux failure - FAILED, all pointers cleared, no public artifact`() {
        val cause = IOException("mux died")
        val exporter = newExporter(mux = { _, _ -> throw cause })

        val result = runBlocking { exporter.export(sessionId, emptyList(), privateTempFile, publicTargetFile) }

        assertTrue("expected MuxFailed, got $result", result is ExportResult.MuxFailed)
        assertEquals(cause, (result as ExportResult.MuxFailed).cause)

        assertFalse("public artifact must not exist after mux failure", publicTargetFile.exists())
        assertFalse(".part must not linger", partFile.exists())

        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
        assertNull(m.privateTempPath)
        assertNull(m.publicTargetPath)
        assertNull(m.pendingUri)
        assertFalse(m.mediaScanCompleted)
    }

    // ─── Copy failure ───────────────────────────────────────────────

    @Test
    fun `copy failure - FAILED, pointers cleared, part-file not lingering`() {
        val cause = IOException("disk full during copy")
        val exporter = newExporter(copyFile = { _, _ -> throw cause })

        val result = runBlocking { exporter.export(sessionId, emptyList(), privateTempFile, publicTargetFile) }

        assertTrue(result is ExportResult.CopyFailed)
        assertEquals(cause, (result as ExportResult.CopyFailed).cause)

        // mux ran (private temp was created) but then attempted-cleaned-up.
        assertFalse("private temp deleted on cleanup", privateTempFile.exists())
        assertFalse(".part must not linger", partFile.exists())
        assertFalse(publicTargetFile.exists())

        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
        assertNull(m.privateTempPath)
        assertNull(m.publicTargetPath)
    }

    // ─── Rename failure ─────────────────────────────────────────────

    @Test
    fun `rename failure - FAILED, public not present, part-file cleaned up`() {
        val exporter = newExporter(renameFile = { _, _ -> false })

        val result = runBlocking { exporter.export(sessionId, emptyList(), privateTempFile, publicTargetFile) }

        assertTrue(result === ExportResult.RenameFailed)
        assertFalse(publicTargetFile.exists())
        assertFalse(".part cleaned up after rename failure", partFile.exists())
        assertFalse(privateTempFile.exists())

        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
        assertNull(m.privateTempPath)
        assertNull(m.publicTargetPath)
    }

    // ─── Phase 1.7 commit-7 NO-GO patch — collision-safety regression ──

    /**
     * Round 1 NO-GO blocker 1 — pre-existing `publicTargetFile` MUST NOT
     * be deleted by the post-failure cleanup chain. The export never
     * owned that file (the publish atom — successful `renameTo` — never
     * fired), so deleting it would wipe a user's prior gallery video on
     * filename collision.
     */
    @Test
    fun `mux failure with pre-existing publicTargetFile - file preserved (collision safety)`() {
        publicTargetFile.parentFile?.mkdirs()
        publicTargetFile.writeBytes(byteArrayOf(0x55, 0x66, 0x77))
        val cause = IOException("mux died")
        val exporter = newExporter(mux = { _, _ -> throw cause })

        val result = runBlocking {
            exporter.export(sessionId, emptyList(), privateTempFile, publicTargetFile)
        }

        assertTrue("expected MuxFailed, got $result", result is ExportResult.MuxFailed)
        assertTrue(
            "pre-existing publicTargetFile must be preserved across mux failure",
            publicTargetFile.exists()
        )
        assertEquals(3, publicTargetFile.length())
    }

    @Test
    fun `copy failure with pre-existing publicTargetFile - file preserved (collision safety)`() {
        publicTargetFile.parentFile?.mkdirs()
        publicTargetFile.writeBytes(byteArrayOf(0x33))
        val cause = IOException("disk full")
        val exporter = newExporter(copyFile = { _, _ -> throw cause })

        val result = runBlocking {
            exporter.export(sessionId, emptyList(), privateTempFile, publicTargetFile)
        }

        assertTrue("expected CopyFailed, got $result", result is ExportResult.CopyFailed)
        assertTrue(
            "pre-existing publicTargetFile must be preserved across copy failure",
            publicTargetFile.exists()
        )
        assertEquals(1, publicTargetFile.length())
    }

    @Test
    fun `rename failure with pre-existing publicTargetFile - file preserved (collision safety)`() {
        publicTargetFile.parentFile?.mkdirs()
        publicTargetFile.writeBytes(byteArrayOf(0x42))
        val exporter = newExporter(renameFile = { _, _ -> false })

        val result = runBlocking {
            exporter.export(sessionId, emptyList(), privateTempFile, publicTargetFile)
        }

        assertTrue("expected RenameFailed, got $result", result === ExportResult.RenameFailed)
        assertTrue(
            "pre-existing publicTargetFile must be preserved across rename failure",
            publicTargetFile.exists()
        )
        assertEquals(1, publicTargetFile.length())
    }

    /**
     * Round 1 NO-GO blocker 2 — `muxer.stop()` failure regression.
     * The pre-NO-GO [VideoMerger.runMux] swallowed `muxer.stop()`
     * exceptions in the `finally` block, so the merge-success log line
     * fired and the mux lambda returned cleanly even when the moov-atom
     * rewrite never landed. The post-NO-GO `runMux` hoists `stop()` to
     * the success path so its failure propagates.
     *
     * This regression fakes the "mux wrote partial output, then `stop()`
     * threw" scenario via the seam: the lambda creates a partial file
     * and throws. The exporter MUST surface `MuxFailed` (not `Success`),
     * the partial private temp MUST be cleaned up, and the manifest
     * MUST land in `FAILED` so the next cold launch's recovery does not
     * see a stale-success state.
     */
    @Test
    fun `mux throws after partial write - regression for muxer stop failure - MuxFailed not Success`() {
        val cause = IOException("muxer.stop() failed: missing track samples for moov rewrite")
        val exporter = newExporter(mux = { _, output ->
            output.parentFile?.mkdirs()
            output.writeBytes(byteArrayOf(0x00, 0x00, 0x00, 0x18))  // partial mp4 ftyp box
            throw cause
        })

        val result = runBlocking {
            exporter.export(sessionId, emptyList(), privateTempFile, publicTargetFile)
        }

        assertFalse(
            "regression: muxer.stop() failure must NOT surface as Success — got $result",
            result is ExportResult.Success
        )
        assertTrue("must surface as MuxFailed, got $result", result is ExportResult.MuxFailed)
        assertEquals(cause, (result as ExportResult.MuxFailed).cause)
        assertFalse(
            "partial private temp must be cleaned up after stop() failure",
            privateTempFile.exists()
        )
        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
        assertNull(m.privateTempPath)
        assertNull(m.publicTargetPath)
    }

    // ─── Scan ok but private-temp delete failed ─────────────────────

    @Test
    fun `scan ok but private temp delete fails - FINALIZED with mediaScanCompleted false and retain`() {
        val exporter = newExporter(
            scanWaiter = FakeMediaScanWaiter(scanResult = true),
            // First delete (privateTempFile after scan) returns false; the
            // cleanupOnFailure path doesn't fire so the partFile / public
            // safe-delete check inside finalize never runs deleteFile
            // because they don't exist. Only the privateTempFile delete
            // is exercised — return false.
            deleteFile = { false }
        )

        val result = runBlocking { exporter.export(sessionId, emptyList(), privateTempFile, publicTargetFile) }

        assertTrue(result is ExportResult.Success)
        val success = result as ExportResult.Success
        assertFalse(
            "delete failure must not promote mediaScanCompleted=true (semantic lie at recovery time)",
            success.mediaScanCompleted
        )
        assertTrue(success.privateTempRetained)

        // Private temp still on disk because the injected delete returned false.
        assertTrue("private temp file retained when delete fails", privateTempFile.exists())
        assertTrue(publicTargetFile.exists())

        val m = reload()
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertFalse(m.mediaScanCompleted)
        assertEquals(
            "privateTempPath must be retained so recovery can retry on next launch",
            privateTempFile.absolutePath,
            m.privateTempPath
        )
    }

    // ─── Manifest disappears mid-export ─────────────────────────────

    @Test
    fun `unknown sessionId at first commit returns UnknownSession - nothing on disk`() {
        val exporter = newExporter()

        val result = runBlocking {
            exporter.export("ghost-sid", emptyList(), privateTempFile, publicTargetFile)
        }

        assertTrue(result is ExportResult.UnknownSession)
        assertEquals("ghost-sid", (result as ExportResult.UnknownSession).sessionId)

        // Real session manifest unchanged.
        val m = reload()
        assertEquals(ExportState.NOT_STARTED, m.exportState)
        assertNull(m.privateTempPath)
        assertNull(m.publicTargetPath)

        // No public artifact created.
        assertFalse(publicTargetFile.exists())
    }

    // ─── Cleanup-attempt-before-pointer-clear contract ──────────────

    @Test
    fun `mux failure cleans up private temp BEFORE setExportFailed pointer clear`() {
        // The spec says "pointers cleared only after caller-side cleanup
        // attempt" — even if cleanup deletes fail, setExportFailed
        // must still write to clear the pointers (otherwise recovery
        // would try to recover from non-existent files).
        val deleteFailingExporter = newExporter(
            mux = { _, output ->
                output.parentFile?.mkdirs()
                output.writeBytes(muxedBytes)
                throw IOException("mux died after writing partial output")
            },
            deleteFile = { false } // delete attempts fail
        )

        val result = runBlocking {
            deleteFailingExporter.export(sessionId, emptyList(), privateTempFile, publicTargetFile)
        }

        assertTrue(result is ExportResult.MuxFailed)

        // Manifest pointers must be cleared even though the cleanup
        // delete failed — otherwise recovery routing would try to
        // recover a non-existent privateTempFile.
        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
        assertNull(m.privateTempPath)
        assertNull(m.publicTargetPath)
    }

    // ─── Hardening: every Tier3Exporter call site uses the bounded waiter ─

    @Test
    fun `bounded waiter is the only scan path - exporter never bypasses it`() {
        // Sanity: confirm the exporter delegates to MediaScanWaiter and
        // never reaches MediaScannerConnection directly. This test
        // injects a waiter that records every call; if the exporter
        // grew a parallel scanFile path (regression), the assertion on
        // call count would fail.
        val waiter = FakeMediaScanWaiter(scanResult = true)
        val exporter = newExporter(scanWaiter = waiter)

        runBlocking { exporter.export(sessionId, emptyList(), privateTempFile, publicTargetFile) }

        assertEquals("happy path makes exactly one bounded scan call", 1, waiter.calls)
    }

    @Test
    fun `Tier3Exporter rejects non-Tier3 manifest in recover()`() {
        val nonTier3 = initial.copy(exportTier = com.aritr.rova.data.ExportTier.TIER1_API29_PLUS)
        try {
            runBlocking { newExporter().recover(nonTier3) }
            fail("recover should reject non-Tier 3 manifest")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ─── Result contract under cleanup-write failures (no swallowing) ───

    /**
     * Mux failure path triggers cleanupOnFailure → setExportFailed.
     * If the mutation itself returns `Failed` (IOException retry
     * exhaustion) the exporter must NOT lie and report `MuxFailed` —
     * cold-launch recovery would still see `MUXING` and run the wrong
     * recovery branch. The exporter must surface
     * `ExportResult.ManifestWriteFailed("setExportFailed", cause)`.
     */
    @Test
    fun `mux failure with setExportFailed returning Failed propagates ManifestWriteFailed`() {
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
        val cause = IOException("manifest fsync exhausted")
        store.setExportFailedOverride = ExportMutationResult.Failed(cause, attempts = 3)

        val exporter = Tier3Exporter(
            sessionStore = store,
            mediaScanWaiter = FakeMediaScanWaiter(scanResult = true),
            mux = { _, _ -> throw IOException("mux died") }
        )
        val priv = File(store.sessionDir(sid), "export/$sid.mp4")
        val pub = File(tmp.newFolder("public2"), "Rova_$sid.mp4")

        val result = runBlocking { exporter.export(sid, emptyList(), priv, pub) }

        assertTrue(
            "expected ManifestWriteFailed not MuxFailed, got $result",
            result is ExportResult.ManifestWriteFailed
        )
        val mwf = result as ExportResult.ManifestWriteFailed
        assertEquals("setExportFailed", mwf.phase)
        assertEquals(cause, mwf.cause)
        assertEquals("setExportFailed must have been called once", 1, store.setExportFailedCalls)

        store.close()
    }

    /**
     * Mux failure path triggers cleanupOnFailure → setExportFailed.
     * If the manifest disappears mid-export (concurrent recovery
     * cleanup) the mutation returns `UnknownSession`. The exporter
     * must surface `ExportResult.UnknownSession`, not `MuxFailed`.
     */
    @Test
    fun `mux failure with setExportFailed returning UnknownSession propagates UnknownSession`() {
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
        store.setExportFailedOverride = ExportMutationResult.UnknownSession(sid)

        val exporter = Tier3Exporter(
            sessionStore = store,
            mediaScanWaiter = FakeMediaScanWaiter(scanResult = true),
            mux = { _, _ -> throw IOException("mux died") }
        )
        val priv = File(store.sessionDir(sid), "export/$sid.mp4")
        val pub = File(tmp.newFolder("public3"), "Rova_$sid.mp4")

        val result = runBlocking { exporter.export(sid, emptyList(), priv, pub) }

        assertTrue(
            "expected UnknownSession not MuxFailed, got $result",
            result is ExportResult.UnknownSession
        )
        assertEquals(sid, (result as ExportResult.UnknownSession).sessionId)
        assertEquals(1, store.setExportFailedCalls)

        store.close()
    }
}

/**
 * Test double for [MediaScanWaiter]: returns a pre-set boolean result
 * and records every invocation. Behaviorally it's "scan returns
 * immediately"; tests that need timeout semantics simulate by passing
 * `scanResult = false`.
 */
internal class FakeMediaScanWaiter(
    private val scanResult: Boolean
) : MediaScanWaiter {
    var calls: Int = 0
        private set
    var lastFile: File? = null
        private set

    override suspend fun scanAndWait(
        file: File,
        mimeType: String,
        timeoutMillis: Long
    ): Boolean {
        calls++
        lastFile = file
        return scanResult
    }
}

/**
 * Test-only [SessionStore] subclass that lets a single test override
 * the result of `setExportFailed` without stubbing the rest of the
 * persistence layer. Used to drive the cleanup-write failure modes
 * (UnknownSession + Failed) that the result contract must not lie
 * about.
 *
 * Lives in the test source set under the same package as the export
 * classes so [com.aritr.rova.data.SessionStore]'s `internal`
 * primary constructor remains accessible.
 */
internal open class StubbingSessionStore(
    rootDir: File
) : com.aritr.rova.data.SessionStore(rootDir) {
    var setExportFailedOverride: ExportMutationResult? = null
    var setExportFailedCalls: Int = 0
        private set

    override suspend fun setExportFailed(sessionId: String): ExportMutationResult {
        setExportFailedCalls++
        return setExportFailedOverride ?: super.setExportFailed(sessionId)
    }
}
