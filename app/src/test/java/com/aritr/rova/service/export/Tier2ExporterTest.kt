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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Phase 1.7 commit-3 — Tier 2 export pipeline regression suite.
 *
 * Tier 2 (API 26–28) shares its publish-and-finalize core
 * ([PreQExportCore]) with Tier 3 — the bulk of the behavior is
 * already covered by [Tier3ExporterTest]. This suite focuses on
 * the contract that matters for Tier 2 dispatch:
 *
 * - happy path: mux → .part → rename → bounded scan → finalize.
 * - scan timeout: keep `privateTempPath`, `mediaScanCompleted=false`,
 *   `exportState=FINALIZED`.
 * - mux / copy / rename failure: cleanup, `exportState=FAILED`.
 * - delete-after-scan failure: deferred to recovery (Tier 3 contract).
 * - unknown session: propagates without writing.
 * - cleanup-write `Failed` / `UnknownSession`: result-bearing
 *   propagation (commit-2 patch contract preserved through
 *   Tier 2 wiring).
 *
 * Tier-level tests (recover rejecting non-Tier-2 manifests) live in
 * [Tier2RecoveryTest].
 *
 * Reuses [FakeMediaScanWaiter] and [StubbingSessionStore] from the
 * Tier 3 test sources via package visibility.
 */
class Tier2ExporterTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var store: SessionStore
    private lateinit var sessionId: String
    private lateinit var initial: SessionManifest

    private lateinit var privateTempFile: File
    private lateinit var publicTargetFile: File
    private val partFile: File get() = File(publicTargetFile.parentFile, "${publicTargetFile.name}.part")

    private val muxedBytes = "TIER2_MUXED".toByteArray()

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
    ) = Tier2Exporter(
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

        val result = runBlocking {
            exporter.export(sessionId, emptyList(), privateTempFile, publicTargetFile)
        }

        assertTrue("expected Success, got $result", result is ExportResult.Success)
        val success = result as ExportResult.Success
        assertTrue(success.mediaScanCompleted)
        assertFalse(success.privateTempRetained)

        assertTrue("public artifact must exist", publicTargetFile.exists())
        assertEquals(muxedBytes.size.toLong(), publicTargetFile.length())
        assertFalse("private temp must be deleted on happy path", privateTempFile.exists())
        assertFalse("part file must be gone after rename", partFile.exists())

        val m = reload()
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertTrue(m.mediaScanCompleted)
        assertNull("privateTempPath cleared on happy path", m.privateTempPath)
        assertEquals(publicTargetFile.absolutePath, m.publicTargetPath)
        assertNull("Tier 2 never sets pendingUri", m.pendingUri)

        assertEquals(1, waiter.calls)
        assertEquals(publicTargetFile.absolutePath, waiter.lastFile?.absolutePath)
    }

    // ─── Scan timeout ───────────────────────────────────────────────

    @Test
    fun `scan timeout - finalized but mediaScanCompleted false and privateTempPath retained`() {
        val exporter = newExporter(scanWaiter = FakeMediaScanWaiter(scanResult = false))

        val result = runBlocking {
            exporter.export(sessionId, emptyList(), privateTempFile, publicTargetFile)
        }

        val success = result as ExportResult.Success
        assertFalse(success.mediaScanCompleted)
        assertTrue(success.privateTempRetained)

        assertTrue(publicTargetFile.exists())
        assertTrue("private temp retained as recovery fuel on timeout", privateTempFile.exists())

        val m = reload()
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertFalse(m.mediaScanCompleted)
        assertEquals(privateTempFile.absolutePath, m.privateTempPath)
        assertEquals(publicTargetFile.absolutePath, m.publicTargetPath)
    }

    // ─── Mux / Copy / Rename failure ────────────────────────────────

    @Test
    fun `mux failure - FAILED, all pointers cleared, no public artifact`() {
        val cause = IOException("mux died")
        val exporter = newExporter(mux = { _, _ -> throw cause })

        val result = runBlocking {
            exporter.export(sessionId, emptyList(), privateTempFile, publicTargetFile)
        }

        assertTrue(result is ExportResult.MuxFailed)
        assertEquals(cause, (result as ExportResult.MuxFailed).cause)
        assertFalse(publicTargetFile.exists())
        assertFalse(partFile.exists())

        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
        assertNull(m.privateTempPath)
        assertNull(m.publicTargetPath)
    }

    @Test
    fun `copy failure - FAILED, pointers cleared, part-file not lingering`() {
        val cause = IOException("disk full during copy")
        val exporter = newExporter(copyFile = { _, _ -> throw cause })

        val result = runBlocking {
            exporter.export(sessionId, emptyList(), privateTempFile, publicTargetFile)
        }

        assertTrue(result is ExportResult.CopyFailed)
        assertEquals(cause, (result as ExportResult.CopyFailed).cause)
        assertFalse(privateTempFile.exists())
        assertFalse(partFile.exists())
        assertFalse(publicTargetFile.exists())

        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
        assertNull(m.privateTempPath)
        assertNull(m.publicTargetPath)
    }

    @Test
    fun `rename failure - FAILED, public not present, part-file cleaned up`() {
        val exporter = newExporter(renameFile = { _, _ -> false })

        val result = runBlocking {
            exporter.export(sessionId, emptyList(), privateTempFile, publicTargetFile)
        }

        assertTrue(result === ExportResult.RenameFailed)
        assertFalse(publicTargetFile.exists())
        assertFalse(partFile.exists())
        assertFalse(privateTempFile.exists())

        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
        assertNull(m.privateTempPath)
        assertNull(m.publicTargetPath)
    }

    // ─── Delete-after-scan failure ──────────────────────────────────

    @Test
    fun `scan ok but private temp delete fails - retains privateTempPath and mediaScanCompleted=false`() {
        val exporter = newExporter(
            scanWaiter = FakeMediaScanWaiter(scanResult = true),
            deleteFile = { false }
        )

        val result = runBlocking {
            exporter.export(sessionId, emptyList(), privateTempFile, publicTargetFile)
        }

        val success = result as ExportResult.Success
        assertFalse(
            "delete failure must not promote mediaScanCompleted=true (semantic lie at recovery time)",
            success.mediaScanCompleted
        )
        assertTrue(success.privateTempRetained)

        assertTrue("private temp file retained when delete fails", privateTempFile.exists())
        assertTrue(publicTargetFile.exists())

        val m = reload()
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertFalse(m.mediaScanCompleted)
        assertEquals(privateTempFile.absolutePath, m.privateTempPath)
    }

    // ─── Unknown session ────────────────────────────────────────────

    @Test
    fun `unknown sessionId at first commit returns UnknownSession - nothing on disk`() {
        val exporter = newExporter()

        val result = runBlocking {
            exporter.export("ghost-sid", emptyList(), privateTempFile, publicTargetFile)
        }

        assertTrue(result is ExportResult.UnknownSession)
        assertEquals("ghost-sid", (result as ExportResult.UnknownSession).sessionId)

        val m = reload()
        assertEquals(ExportState.NOT_STARTED, m.exportState)
        assertNull(m.privateTempPath)
        assertNull(m.publicTargetPath)

        assertFalse(publicTargetFile.exists())
    }

    // ─── Phase 6.1b T12 — per-side routing ──────────────────────────

    /**
     * Phase 6.1b T12 — Tier 2 mirror of the Tier 3 per-side routing test.
     * When `side != null`, manifest writes route to per-side mutators;
     * shared `exportState` stays at NOT_STARTED (T13 owns the final
     * shared write); shared `privateTempPath` / `publicTargetPath` are
     * not touched.
     */
    @Test
    fun `export with side PORTRAIT routes to per-side mutators leaving shared exportState NOT_STARTED`() {
        val exporter = newExporter()

        val result = runBlocking {
            exporter.export(
                sessionId,
                emptyList(),
                privateTempFile,
                publicTargetFile,
                com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT
            )
        }

        assertTrue("expected Success, got $result", result is ExportResult.Success)
        val m = reload()
        assertEquals(ExportState.NOT_STARTED, m.exportState)
        assertNull(m.privateTempPath)
        assertNull(m.publicTargetPath)
        assertFalse(m.mediaScanCompleted)
        assertEquals(publicTargetFile.absolutePath, m.portraitPublicTargetPath)
        assertTrue(m.portraitMediaScanCompleted)
        assertNull(m.landscapePublicTargetPath)
    }

    @Test
    fun `export with side null preserves single-mode shared mutator writes`() {
        val exporter = newExporter()

        val result = runBlocking {
            exporter.export(sessionId, emptyList(), privateTempFile, publicTargetFile)
        }

        assertTrue(result is ExportResult.Success)
        val m = reload()
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertEquals(publicTargetFile.absolutePath, m.publicTargetPath)
        assertTrue(m.mediaScanCompleted)
        assertNull(m.portraitPublicTargetPath)
    }

    // ─── Cleanup-write contract (commit-2 patch through Tier 2) ─────

    @Test
    fun `mux failure with setExportFailed returning Failed propagates ManifestWriteFailed`() {
        val store = StubbingSessionStore(tmp.newFolder("videos2"))
        val seed = store.createSession(
            config = SessionConfig(
                durationSeconds = 30,
                intervalMinutes = 5,
                resolution = "FHD",
                loopCount = 4
            )
        )
        val sid = seed.sessionId
        val cause = IOException("manifest fsync exhausted")
        store.setExportFailedOverride = ExportMutationResult.Failed(cause, attempts = 3)

        val exporter = Tier2Exporter(
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
        assertEquals(1, store.setExportFailedCalls)

        store.close()
    }

    @Test
    fun `mux failure with setExportFailed returning UnknownSession propagates UnknownSession`() {
        val store = StubbingSessionStore(tmp.newFolder("videos3"))
        val seed = store.createSession(
            config = SessionConfig(
                durationSeconds = 30,
                intervalMinutes = 5,
                resolution = "FHD",
                loopCount = 4
            )
        )
        val sid = seed.sessionId
        store.setExportFailedOverride = ExportMutationResult.UnknownSession(sid)

        val exporter = Tier2Exporter(
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
