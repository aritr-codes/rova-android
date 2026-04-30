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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException

/**
 * Phase 1.7 commit-4 — Tier 1 (API 29+) live-export regression suite.
 *
 * Covers the live-export contract from ADR 0003 §"Recovery routing"
 * (Tier 1 column) plus the hard requirements from the commit-4 spec:
 * - happy path ordering: insert → setExportPending → FD mux ("rw") →
 *   IS_PENDING=0 → setExportFinalized.
 * - openFileDescriptor mode recorded as "rw".
 * - insert returns null / throws: PendingInsertFailed; no manifest write
 *   beyond setExportFailed cleanup; no row to delete.
 * - insert ok, setExportPending fails: pending row deleted, no mux,
 *   ManifestWriteFailed("setExportPending") or UnknownSession.
 * - mux failure: pending row deleted, setExportFailed, MuxFailed (or
 *   cleanup-mapped failure if setExportFailed itself fails).
 * - finalizePendingRow fails (returns false / throws): row deleted,
 *   setExportFailed, FinalizeFailed.
 * - setExportFinalized fails at end: ManifestWriteFailed propagation.
 *
 * No `MediaStore` / `ContentResolver` / `MediaMuxer` is exercised at
 * runtime — every Android-platform call is replaced by an injected
 * lambda. The exporter's correctness is in the orchestration: ordering
 * of mutator calls, side-effect sequencing, and ExportResult mapping.
 *
 * `MediaScanWaiter` is intentionally absent from [Tier1Exporter]'s
 * constructor — Tier 1 uses `MediaStore`'s automatic indexing on
 * `IS_PENDING=0` and never calls `MediaScannerConnection.scanFile`.
 * The class shape is the structural proof; the global lint
 * `checkScanFileBoundedWait` catches any source-level regression.
 */
class Tier1ExporterTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var store: StubbingSessionStoreTier1
    private lateinit var sessionId: String
    private lateinit var initial: SessionManifest

    /** Backing file for the test's FD seam — mux writes here so we can verify bytes hit a real FD. */
    private lateinit var fdBackingFile: File

    private val testUri = "content://media/external/video/media/42"

    @Before
    fun setUp() {
        store = StubbingSessionStoreTier1(tmp.newFolder("videos"))
        val created = store.createSession(
            config = SessionConfig(
                durationSeconds = 30,
                intervalMinutes = 5,
                resolution = "FHD",
                loopCount = 4
            )
        )
        sessionId = created.sessionId
        // Force tier to TIER1 — under JVM unit tests SDK_INT=0 so
        // currentExportTier() returns TIER3 by default. Tier 1 export()
        // does not check the manifest tier, but writing the right tier
        // keeps recovery-side parity.
        initial = created.copy(exportTier = ExportTier.TIER1_API29_PLUS)
        writeManifest(initial)
        fdBackingFile = File(tmp.newFolder("fd_backing"), "muxed.mp4")
    }

    @After
    fun tearDown() {
        store.close()
    }

    private fun writeManifest(m: SessionManifest) {
        val target = File(store.sessionDir(m.sessionId), "manifest.json")
        target.writeText(m.toJson().toString(2))
    }

    private fun reload(): SessionManifest =
        requireNotNull(store.loadManifest(sessionId)) { "manifest disappeared" }

    /**
     * Records every seam invocation in call order plus the FD-mode
     * argument so tests can assert "rw". Backed by a real on-disk file
     * for the FD so the mux lambda can write through `FileOutputStream.fd`
     * without touching Android types.
     */
    private inner class Recorder {
        val events = mutableListOf<String>()
        var insertReturn: String? = testUri
        var insertThrow: Throwable? = null
        var muxThrow: Throwable? = null
        var muxBytes: ByteArray = "MOCK_TIER1_OUTPUT".toByteArray()
        var finalizeResult: Tier1FinalizeResult = Tier1FinalizeResult.Finalized
        var deleteReturn: Boolean = true
        var deleteThrow: Throwable? = null
        val recordedFdModes = mutableListOf<String>()

        val insert: suspend (String) -> String? = { sid ->
            events += "insert($sid)"
            insertThrow?.let { throw it }
            insertReturn
        }

        val withFd: suspend (
            String,
            String,
            suspend (FileDescriptor) -> Unit
        ) -> Unit = { uri, mode, block ->
            events += "openFd($uri, $mode)"
            recordedFdModes += mode
            // Real FD via FileOutputStream so the mux lambda can write
            // through it. Mirror Tier1AndroidOps.withPendingFd's
            // close-in-finally semantics so the recorder logs closeFd
            // on success AND on throw.
            try {
                FileOutputStream(fdBackingFile).use { fos ->
                    block(fos.fd)
                }
            } finally {
                events += "closeFd($uri)"
            }
        }

        val mux: suspend (List<File>, FileDescriptor) -> Unit = { _, fd ->
            events += "mux"
            muxThrow?.let { throw it }
            FileOutputStream(fd).use { it.write(muxBytes) }
        }

        val finalize: suspend (String) -> Tier1FinalizeResult = { uri ->
            events += "finalize($uri)"
            finalizeResult
        }

        val delete: suspend (String) -> Boolean = { uri ->
            events += "delete($uri)"
            deleteThrow?.let { throw it }
            deleteReturn
        }
    }

    private fun newExporter(r: Recorder): Tier1Exporter = Tier1Exporter(
        sessionStore = store,
        insertPendingRow = r.insert,
        withPendingFd = r.withFd,
        mux = r.mux,
        finalizePendingRow = r.finalize,
        deletePendingRow = r.delete
    )

    // ─── Happy path ─────────────────────────────────────────────────

    @Test
    fun `happy path - insert setExportPending mux finalize FINALIZED in order`() {
        val r = Recorder()
        val exporter = newExporter(r)

        val result = runBlocking { exporter.export(sessionId, emptyList()) }

        assertTrue("expected Success, got $result", result is ExportResult.Success)
        val success = result as ExportResult.Success
        assertTrue("Tier 1 success implies indexing complete", success.mediaScanCompleted)
        assertFalse("Tier 1 has no private temp", success.privateTempRetained)

        // Call ordering — exact sequence per spec.
        assertEquals(
            listOf(
                "insert($sessionId)",
                "openFd($testUri, rw)",
                "mux",
                "closeFd($testUri)",
                "finalize($testUri)"
            ),
            r.events
        )

        // FD mode argument is "rw".
        assertEquals(listOf("rw"), r.recordedFdModes)

        // No deleteRow on happy path.
        assertEquals(0, r.events.count { it.startsWith("delete(") })

        // Manifest side-effects.
        val m = reload()
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertEquals("pendingUri retained on Tier 1 success", testUri, m.pendingUri)
        assertNull("Tier 1 never writes privateTempPath", m.privateTempPath)
        assertNull("Tier 1 never writes publicTargetPath", m.publicTargetPath)
        assertFalse("Tier 1 never sets manifest mediaScanCompleted field", m.mediaScanCompleted)

        // Mux actually wrote bytes through the FD.
        assertEquals(r.muxBytes.size.toLong(), fdBackingFile.length())
    }

    // ─── Insert returns null ─────────────────────────────────────────

    @Test
    fun `insert returns null - PendingInsertFailed without mux without manifest write past failed`() {
        val r = Recorder().apply { insertReturn = null }
        val exporter = newExporter(r)

        val result = runBlocking { exporter.export(sessionId, emptyList()) }

        assertTrue("expected PendingInsertFailed, got $result", result is ExportResult.PendingInsertFailed)
        assertNull((result as ExportResult.PendingInsertFailed).cause)

        // No setExportPending, no mux, no finalize, no delete (no row to delete).
        assertEquals(listOf("insert($sessionId)"), r.events)
        assertEquals(0, store.setExportPendingCalls)
        assertEquals(1, store.setExportFailedCalls)

        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
        assertNull(m.pendingUri)
    }

    @Test
    fun `insert throws - PendingInsertFailed with cause`() {
        val cause = IOException("ContentResolver.insert blew up")
        val r = Recorder().apply { insertThrow = cause }
        val exporter = newExporter(r)

        val result = runBlocking { exporter.export(sessionId, emptyList()) }

        assertTrue(result is ExportResult.PendingInsertFailed)
        assertEquals(cause, (result as ExportResult.PendingInsertFailed).cause)

        assertEquals(listOf("insert($sessionId)"), r.events)
        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
    }

    // ─── setExportPending fails ──────────────────────────────────────

    @Test
    fun `insert ok but setExportPending Failed - row deleted ManifestWriteFailed no mux`() {
        val cause = IOException("manifest fsync exhausted")
        store.setExportPendingOverride = ExportMutationResult.Failed(cause, attempts = 3)
        val r = Recorder()
        val exporter = newExporter(r)

        val result = runBlocking { exporter.export(sessionId, emptyList()) }

        assertTrue("expected ManifestWriteFailed, got $result", result is ExportResult.ManifestWriteFailed)
        val mwf = result as ExportResult.ManifestWriteFailed
        assertEquals("setExportPending", mwf.phase)
        assertEquals(cause, mwf.cause)

        // Row deleted, mux NEVER ran.
        assertEquals(
            listOf("insert($sessionId)", "delete($testUri)"),
            r.events
        )
        assertEquals(1, store.setExportPendingCalls)
        // No setExportFailed call — the manifest write that failed was
        // setExportPending; we don't follow it with another retry-prone
        // setExportFailed write (the manifest is already untouched).
        assertEquals(0, store.setExportFailedCalls)
    }

    @Test
    fun `insert ok but setExportPending UnknownSession - row deleted UnknownSession no mux`() {
        store.setExportPendingOverride = ExportMutationResult.UnknownSession(sessionId)
        val r = Recorder()
        val exporter = newExporter(r)

        val result = runBlocking { exporter.export(sessionId, emptyList()) }

        assertTrue(result is ExportResult.UnknownSession)
        assertEquals(sessionId, (result as ExportResult.UnknownSession).sessionId)

        assertEquals(
            listOf("insert($sessionId)", "delete($testUri)"),
            r.events
        )
    }

    // ─── Mux failure ─────────────────────────────────────────────────

    @Test
    fun `mux throws - row deleted setExportFailed MuxFailed`() {
        val cause = IOException("mux died")
        val r = Recorder().apply { muxThrow = cause }
        val exporter = newExporter(r)

        val result = runBlocking { exporter.export(sessionId, emptyList()) }

        assertTrue("expected MuxFailed, got $result", result is ExportResult.MuxFailed)
        assertEquals(cause, (result as ExportResult.MuxFailed).cause)

        // Ordering: insert, open, mux (throws), close (FD seam closes on exit), delete row.
        // No finalize.
        assertTrue(r.events.contains("insert($sessionId)"))
        assertTrue(r.events.contains("openFd($testUri, rw)"))
        assertTrue(r.events.contains("mux"))
        assertTrue("FD must close even on mux throw", r.events.contains("closeFd($testUri)"))
        assertTrue(r.events.contains("delete($testUri)"))
        assertFalse("finalize must not run after mux throw", r.events.any { it.startsWith("finalize") })

        assertEquals(1, store.setExportFailedCalls)
        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
        assertNull(m.pendingUri)
    }

    @Test
    fun `mux throws and setExportFailed Failed - propagates ManifestWriteFailed not MuxFailed`() {
        val muxCause = IOException("mux died")
        val cleanupCause = IOException("manifest fsync exhausted")
        store.setExportFailedOverride = ExportMutationResult.Failed(cleanupCause, attempts = 3)
        val r = Recorder().apply { muxThrow = muxCause }
        val exporter = newExporter(r)

        val result = runBlocking { exporter.export(sessionId, emptyList()) }

        assertTrue(
            "expected ManifestWriteFailed, got $result",
            result is ExportResult.ManifestWriteFailed
        )
        val mwf = result as ExportResult.ManifestWriteFailed
        assertEquals("setExportFailed", mwf.phase)
        assertEquals(cleanupCause, mwf.cause)
        assertEquals(1, store.setExportFailedCalls)
    }

    @Test
    fun `mux throws and setExportFailed UnknownSession - propagates UnknownSession`() {
        store.setExportFailedOverride = ExportMutationResult.UnknownSession(sessionId)
        val r = Recorder().apply { muxThrow = IOException("mux died") }
        val exporter = newExporter(r)

        val result = runBlocking { exporter.export(sessionId, emptyList()) }

        assertTrue(result is ExportResult.UnknownSession)
        assertEquals(sessionId, (result as ExportResult.UnknownSession).sessionId)
    }

    // ─── Finalize failure ────────────────────────────────────────────

    @Test
    fun `live finalize NoRowsUpdated - row deleted FinalizeFailed null cause manifest FAILED`() {
        // Live just inserted the row in step 1, so a 0-rows-updated
        // result here means the row went missing under us. Live treats
        // it strictly as failure (unlike recovery, which interprets
        // post-validate NoRowsUpdated as already-finalized).
        val r = Recorder().apply { finalizeResult = Tier1FinalizeResult.NoRowsUpdated }
        val exporter = newExporter(r)

        val result = runBlocking { exporter.export(sessionId, emptyList()) }

        assertTrue("expected FinalizeFailed, got $result", result is ExportResult.FinalizeFailed)
        assertNull((result as ExportResult.FinalizeFailed).cause)

        // Mux ran, finalize ran (NoRowsUpdated), then row deleted.
        assertTrue(r.events.contains("mux"))
        assertTrue(r.events.contains("finalize($testUri)"))
        assertTrue(r.events.contains("delete($testUri)"))

        assertEquals(1, store.setExportFailedCalls)
        val m = reload()
        assertEquals("must NOT write FINALIZED", ExportState.FAILED, m.exportState)
        assertNull(m.pendingUri)
    }

    @Test
    fun `live finalize Failed - row deleted FinalizeFailed with cause`() {
        val cause = IOException("update threw")
        val r = Recorder().apply { finalizeResult = Tier1FinalizeResult.Failed(cause) }
        val exporter = newExporter(r)

        val result = runBlocking { exporter.export(sessionId, emptyList()) }

        assertTrue(result is ExportResult.FinalizeFailed)
        assertEquals(cause, (result as ExportResult.FinalizeFailed).cause)
        assertTrue(r.events.contains("delete($testUri)"))
    }

    // ─── setExportFinalized fails at the end ─────────────────────────

    @Test
    fun `setExportFinalized Failed - ManifestWriteFailed surfaces`() {
        val cause = IOException("final write exhausted")
        store.setExportFinalizedOverride = ExportMutationResult.Failed(cause, attempts = 3)
        val r = Recorder()
        val exporter = newExporter(r)

        val result = runBlocking { exporter.export(sessionId, emptyList()) }

        assertTrue(
            "expected ManifestWriteFailed, got $result",
            result is ExportResult.ManifestWriteFailed
        )
        val mwf = result as ExportResult.ManifestWriteFailed
        assertEquals("setExportFinalized", mwf.phase)
        assertEquals(cause, mwf.cause)

        // Mux + finalize ran successfully; only the manifest FINALIZED
        // write failed. Row is NOT deleted — the artifact is on disk
        // and visible to the user (IS_PENDING=0 already flipped).
        assertEquals(0, r.events.count { it.startsWith("delete(") })
        assertTrue(r.events.contains("finalize($testUri)"))
    }

    // ─── Structural: no MediaScanWaiter dependency ───────────────────

    @Test
    fun `Tier1Exporter constructor has no MediaScanWaiter parameter`() {
        // The Tier 1 pipeline relies on MediaStore's automatic indexing
        // when IS_PENDING=0 — it does NOT call
        // MediaScannerConnection.scanFile. The class shape is the
        // structural guarantee; the checkScanFileBoundedWait lint
        // catches any source-level regression in the rest of the app.
        val ctorParams = Tier1Exporter::class.java.declaredConstructors
            .flatMap { it.parameterTypes.toList() }
            .map { it.name }
        assertFalse(
            "Tier1Exporter must not depend on MediaScanWaiter",
            ctorParams.any { it.contains("MediaScanWaiter") }
        )
        assertNotNull(ctorParams)
    }

    // ─── Sanity: bytes flow through FD seam end-to-end ───────────────

    @Test
    fun `mux writes bytes through the FD seam not through some side channel`() {
        val payload = "TIER1_PAYLOAD_BYTES".toByteArray()
        val r = Recorder().apply { muxBytes = payload }
        val exporter = newExporter(r)

        val result = runBlocking { exporter.export(sessionId, emptyList()) }

        assertTrue(result is ExportResult.Success)
        assertEquals(payload.size.toLong(), fdBackingFile.length())
        assertEquals(payload.toList(), fdBackingFile.readBytes().toList())
    }
}

/**
 * Test-only [com.aritr.rova.data.SessionStore] subclass extending the
 * commit-2 [StubbingSessionStore] with override hooks for every
 * Tier 1-relevant export mutator. Used to drive the cleanup-write
 * failure modes (UnknownSession + Failed) on every commit point so the
 * result contract never lies about manifest state.
 *
 * Lives in the test source set under the same package as the export
 * classes so [com.aritr.rova.data.SessionStore]'s `internal` primary
 * constructor is reachable.
 */
internal open class StubbingSessionStoreTier1(
    rootDir: File
) : StubbingSessionStore(rootDir) {
    var setExportPendingOverride: ExportMutationResult? = null
    var setExportPendingCalls: Int = 0
        private set

    var setExportFinalizedOverride: ExportMutationResult? = null
    var setExportFinalizedCalls: Int = 0
        private set

    override suspend fun setExportPending(
        sessionId: String,
        pendingUri: String
    ): ExportMutationResult {
        setExportPendingCalls++
        return setExportPendingOverride ?: super.setExportPending(sessionId, pendingUri)
    }

    override suspend fun setExportFinalized(
        sessionId: String,
        clearPrivateTempPath: Boolean
    ): ExportMutationResult {
        setExportFinalizedCalls++
        return setExportFinalizedOverride ?: super.setExportFinalized(sessionId, clearPrivateTempPath)
    }
}
