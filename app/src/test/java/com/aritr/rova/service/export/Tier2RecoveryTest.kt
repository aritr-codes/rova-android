package com.aritr.rova.service.export

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
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

/**
 * Phase 1.7 commit-3 — Tier 2 cold-launch recovery routine,
 * ADR 0003 §"Recovery routing" Cases A/B/C/D.
 *
 * Tier 2 shares its recovery logic with Tier 3 via [PreQExportCore];
 * this suite verifies the wiring (recover() rejects non-Tier-2
 * manifests, accepts TIER2_API26_28) and exercises the four cases
 * end-to-end through the Tier 2 dispatch.
 */
class Tier2RecoveryTest {

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
        publicTargetFile = File(tmp.newFolder("public_movies_rova"), "Rova_$sessionId.mp4")
    }

    @After
    fun tearDown() {
        store.close()
    }

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
    ) = Tier2Exporter(
        sessionStore = store,
        mediaScanWaiter = FakeMediaScanWaiter(scanResult),
        mux = mux
    )

    private fun reload(): SessionManifest =
        requireNotNull(store.loadManifest(sessionId)) { "manifest disappeared" }

    private fun tier2Manifest(
        privateTempPath: String? = privateTempFile.absolutePath,
        publicTargetPath: String? = publicTargetFile.absolutePath,
        exportState: ExportState = ExportState.MUXING,
        mediaScanCompleted: Boolean = false
    ): SessionManifest = initial.copy(
        exportTier = ExportTier.TIER2_API26_28,
        privateTempPath = privateTempPath,
        publicTargetPath = publicTargetPath,
        mediaScanCompleted = mediaScanCompleted,
        exportState = exportState
    )

    // ─── Case A ─────────────────────────────────────────────────────

    @Test
    fun `case A - publicTargetFile exists and scan succeeds - finalize cleanly`() {
        publicTargetFile.parentFile?.mkdirs()
        publicTargetFile.writeBytes("ALREADY_PUBLISHED".toByteArray())
        privateTempFile.parentFile?.mkdirs()
        privateTempFile.writeBytes("STILL_HERE".toByteArray())
        val pre = tier2Manifest(exportState = ExportState.FINALIZED, mediaScanCompleted = false)
        writePreState(pre)

        val result = runBlocking { newExporter(scanResult = true).recover(pre) }

        assertTrue("expected Resumed, got $result", result is RecoveryResult.Resumed)
        val export = (result as RecoveryResult.Resumed).export
        val success = export as ExportResult.Success
        assertTrue(success.mediaScanCompleted)
        assertFalse(success.privateTempRetained)

        assertTrue(publicTargetFile.exists())
        assertFalse(privateTempFile.exists())

        val m = reload()
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertTrue(m.mediaScanCompleted)
        assertNull(m.privateTempPath)
        assertEquals(publicTargetFile.absolutePath, m.publicTargetPath)
    }

    // ─── Case B ─────────────────────────────────────────────────────

    @Test
    fun `case B - part exists with valid private temp - resume from copy step`() {
        partFile.parentFile?.mkdirs()
        partFile.writeBytes("HALF_COPIED".toByteArray())
        privateTempFile.parentFile?.mkdirs()
        privateTempFile.writeBytes("VALID_MERGED_OUTPUT".toByteArray())
        val pre = tier2Manifest(exportState = ExportState.COPYING)
        writePreState(pre)

        val result = runBlocking { newExporter(scanResult = true).recover(pre) }

        assertTrue(result is RecoveryResult.Resumed)
        val success = (result as RecoveryResult.Resumed).export as ExportResult.Success
        assertTrue(success.mediaScanCompleted)

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
        val pre = tier2Manifest(exportState = ExportState.COPYING)
        writePreState(pre)

        val result = runBlocking { newExporter().recover(pre) }

        assertTrue(result === RecoveryResult.Abandoned)
        assertFalse(partFile.exists())
        assertFalse(publicTargetFile.exists())

        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
        assertNull(m.privateTempPath)
        assertNull(m.publicTargetPath)
    }

    // ─── Case C ─────────────────────────────────────────────────────

    @Test
    fun `case C - only privateTempFile exists and is valid - resume from copy step`() {
        privateTempFile.parentFile?.mkdirs()
        privateTempFile.writeBytes("MUX_DONE_COPY_NEVER_STARTED".toByteArray())
        val pre = tier2Manifest(exportState = ExportState.MUXING)
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

    // ─── Case D ─────────────────────────────────────────────────────

    @Test
    fun `case D - no public, no part, no private temp - abandon`() {
        val pre = tier2Manifest(exportState = ExportState.MUXING)
        writePreState(pre)

        val result = runBlocking { newExporter().recover(pre) }

        assertTrue(result === RecoveryResult.Abandoned)

        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
        assertNull(m.privateTempPath)
        assertNull(m.publicTargetPath)
        assertNull(m.pendingUri)
        assertFalse(m.mediaScanCompleted)
    }

    // ─── Tier guard ─────────────────────────────────────────────────

    @Test
    fun `Tier2Exporter rejects non-Tier2 manifest in recover()`() {
        val nonTier2 = initial.copy(exportTier = ExportTier.TIER3_API24_25)
        try {
            runBlocking { newExporter().recover(nonTier2) }
            fail("recover should reject non-Tier 2 manifest")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
