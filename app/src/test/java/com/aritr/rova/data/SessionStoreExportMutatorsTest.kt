package com.aritr.rova.data

import com.aritr.rova.data.ExportMutationResult.UnknownSession
import com.aritr.rova.data.ExportMutationResult.Wrote
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
 * Phase 1.7 commit-1 — `SessionStore` export-mutator regression suite
 * (ADR 0003 §"Recovery routing" partner).
 *
 * Each mutator is asserted against the explicit pointer-lifecycle table
 * fixed in the Phase 1.7 design (ADR 0003 §"FD Mode Amendment"-adjacent
 * lifecycle definition):
 * - the documented field set transitions exactly as specified,
 * - **no other export field is mutated** ("no unintended clears"),
 * - non-export fields (`exportTier`, `audioMode`, `terminated`,
 *   `segments`, `startedAt`, `config`) survive unchanged,
 * - unknown `sessionId` returns [ExportMutationResult.UnknownSession]
 *   instead of throwing, and does not touch any other session's
 *   manifest.
 *
 * The "no unintended clears" tests pre-load the manifest with a
 * mixed-tier field set that no single mutator could legitimately
 * produce (e.g. both `pendingUri` and `privateTempPath` populated).
 * That state is impossible at runtime under the normal Phase 1.7 flow,
 * but it is the only way to exercise every potential clear path of a
 * mutator under test.
 */
class SessionStoreExportMutatorsTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var store: SessionStore
    private lateinit var sessionId: String
    private lateinit var initial: SessionManifest

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
    }

    @After
    fun tearDown() {
        store.close()
    }

    /**
     * Bypass the (private) `writeManifestAtomic` to inject a pre-state
     * for "no unintended clears" tests. Production mutators each take
     * one field-set transition; this helper synthesizes the mixed-tier
     * prior state that no single mutator could produce.
     */
    private fun writePreState(updated: SessionManifest) {
        val target = File(store.sessionDir(updated.sessionId), "manifest.json")
        target.writeText(updated.toJson().toString(2))
    }

    private fun reload(): SessionManifest =
        requireNotNull(store.loadManifest(sessionId)) { "manifest disappeared" }

    private val FULLY_POPULATED_PRIOR get() = initial.copy(
        privateTempPath = "/tmp/prior.mp4",
        publicTargetPath = "/storage/Movies/Rova/prior.mp4",
        pendingUri = "content://media/external/video/prior",
        mediaScanCompleted = true,
        exportState = ExportState.COPYING
    )

    // ─── setExportPending ───────────────────────────────────────────

    @Test
    fun `setExportPending writes pendingUri + MUXING and clears no other field`() {
        writePreState(FULLY_POPULATED_PRIOR)

        val result = runBlocking {
            store.setExportPending(sessionId, "content://media/external/video/42")
        }

        assertTrue(result is Wrote)
        val m = reload()
        assertEquals("content://media/external/video/42", m.pendingUri)
        assertEquals(ExportState.MUXING, m.exportState)
        // No unintended clears
        assertEquals("/tmp/prior.mp4", m.privateTempPath)
        assertEquals("/storage/Movies/Rova/prior.mp4", m.publicTargetPath)
        assertTrue(m.mediaScanCompleted)
        assertNonExportFieldsPreserved(m)
    }

    @Test
    fun `setExportPending on unknown sessionId returns UnknownSession and does not touch real manifest`() {
        val result = runBlocking { store.setExportPending("ghost-session-id", "content://x") }
        assertTrue(result is UnknownSession)
        assertEquals("ghost-session-id", (result as UnknownSession).sessionId)
        // Real session unchanged
        val m = reload()
        assertEquals(ExportState.NOT_STARTED, m.exportState)
        assertNull(m.pendingUri)
    }

    // ─── setExportPrivateTarget ─────────────────────────────────────

    @Test
    fun `setExportPrivateTarget writes both paths + MUXING and leaves pendingUri + scan flag intact`() {
        writePreState(FULLY_POPULATED_PRIOR)

        val result = runBlocking {
            store.setExportPrivateTarget(
                sessionId,
                privateTempPath = "/tmp/new.mp4",
                publicTargetPath = "/storage/Movies/Rova/new.mp4"
            )
        }

        assertTrue(result is Wrote)
        val m = reload()
        assertEquals("/tmp/new.mp4", m.privateTempPath)
        assertEquals("/storage/Movies/Rova/new.mp4", m.publicTargetPath)
        assertEquals(ExportState.MUXING, m.exportState)
        // No unintended clears
        assertEquals("content://media/external/video/prior", m.pendingUri)
        assertTrue(m.mediaScanCompleted)
        assertNonExportFieldsPreserved(m)
    }

    @Test
    fun `setExportPrivateTarget on unknown sessionId returns UnknownSession`() {
        val result = runBlocking {
            store.setExportPrivateTarget("ghost", "/tmp/x.mp4", "/storage/y.mp4")
        }
        assertTrue(result is UnknownSession)
        assertEquals(ExportState.NOT_STARTED, reload().exportState)
        assertNull(reload().privateTempPath)
        assertNull(reload().publicTargetPath)
    }

    // ─── setExportCopying ───────────────────────────────────────────

    @Test
    fun `setExportCopying writes COPYING and clears no other field`() {
        writePreState(FULLY_POPULATED_PRIOR.copy(exportState = ExportState.MUXING))

        val result = runBlocking { store.setExportCopying(sessionId) }

        assertTrue(result is Wrote)
        val m = reload()
        assertEquals(ExportState.COPYING, m.exportState)
        // Every other field preserved
        assertEquals("/tmp/prior.mp4", m.privateTempPath)
        assertEquals("/storage/Movies/Rova/prior.mp4", m.publicTargetPath)
        assertEquals("content://media/external/video/prior", m.pendingUri)
        assertTrue(m.mediaScanCompleted)
        assertNonExportFieldsPreserved(m)
    }

    @Test
    fun `setExportCopying on unknown sessionId returns UnknownSession`() {
        val result = runBlocking { store.setExportCopying("ghost") }
        assertTrue(result is UnknownSession)
        assertEquals(ExportState.NOT_STARTED, reload().exportState)
    }

    // ─── setMediaScanCompleted ──────────────────────────────────────

    @Test
    fun `setMediaScanCompleted flips flag true and clears no other field`() {
        writePreState(FULLY_POPULATED_PRIOR.copy(mediaScanCompleted = false))

        val result = runBlocking { store.setMediaScanCompleted(sessionId) }

        assertTrue(result is Wrote)
        val m = reload()
        assertTrue(m.mediaScanCompleted)
        // Every other field preserved
        assertEquals(ExportState.COPYING, m.exportState)
        assertEquals("/tmp/prior.mp4", m.privateTempPath)
        assertEquals("/storage/Movies/Rova/prior.mp4", m.publicTargetPath)
        assertEquals("content://media/external/video/prior", m.pendingUri)
        assertNonExportFieldsPreserved(m)
    }

    @Test
    fun `setMediaScanCompleted is idempotent (already-true pre-state)`() {
        writePreState(FULLY_POPULATED_PRIOR.copy(mediaScanCompleted = true))
        val result = runBlocking { store.setMediaScanCompleted(sessionId) }
        assertTrue(result is Wrote)
        assertTrue(reload().mediaScanCompleted)
    }

    @Test
    fun `setMediaScanCompleted on unknown sessionId returns UnknownSession`() {
        val result = runBlocking { store.setMediaScanCompleted("ghost") }
        assertTrue(result is UnknownSession)
        assertFalse(reload().mediaScanCompleted)
    }

    // ─── setExportFinalized ─────────────────────────────────────────

    @Test
    fun `setExportFinalized clearPrivateTempPath=true writes FINALIZED and clears only privateTempPath`() {
        writePreState(FULLY_POPULATED_PRIOR.copy(exportState = ExportState.COPYING))

        val result = runBlocking {
            store.setExportFinalized(sessionId, clearPrivateTempPath = true)
        }

        assertTrue(result is Wrote)
        val m = reload()
        assertEquals(ExportState.FINALIZED, m.exportState)
        assertNull(m.privateTempPath)
        // No unintended clears: publicTargetPath / pendingUri / mediaScanCompleted retained
        assertEquals("/storage/Movies/Rova/prior.mp4", m.publicTargetPath)
        assertEquals("content://media/external/video/prior", m.pendingUri)
        assertTrue(m.mediaScanCompleted)
        assertNonExportFieldsPreserved(m)
    }

    @Test
    fun `setExportFinalized clearPrivateTempPath=false writes FINALIZED and retains all pointers`() {
        writePreState(FULLY_POPULATED_PRIOR.copy(exportState = ExportState.COPYING))

        val result = runBlocking {
            store.setExportFinalized(sessionId, clearPrivateTempPath = false)
        }

        assertTrue(result is Wrote)
        val m = reload()
        assertEquals(ExportState.FINALIZED, m.exportState)
        // No unintended clears: every pointer + mediaScanCompleted retained
        assertEquals("/tmp/prior.mp4", m.privateTempPath)
        assertEquals("/storage/Movies/Rova/prior.mp4", m.publicTargetPath)
        assertEquals("content://media/external/video/prior", m.pendingUri)
        assertTrue(m.mediaScanCompleted)
        assertNonExportFieldsPreserved(m)
    }

    @Test
    fun `setExportFinalized clearPrivateTempPath=true is no-op-clear when privateTempPath already null`() {
        writePreState(FULLY_POPULATED_PRIOR.copy(privateTempPath = null))

        val result = runBlocking {
            store.setExportFinalized(sessionId, clearPrivateTempPath = true)
        }

        assertTrue(result is Wrote)
        val m = reload()
        assertNull(m.privateTempPath)
        assertEquals(ExportState.FINALIZED, m.exportState)
    }

    @Test
    fun `setExportFinalized on unknown sessionId returns UnknownSession`() {
        val result = runBlocking { store.setExportFinalized("ghost", clearPrivateTempPath = true) }
        assertTrue(result is UnknownSession)
        assertEquals(ExportState.NOT_STARTED, reload().exportState)
    }

    // ─── setExportFailed ────────────────────────────────────────────

    @Test
    fun `setExportFailed writes FAILED and clears all three pointers + resets mediaScanCompleted`() {
        writePreState(FULLY_POPULATED_PRIOR.copy(exportState = ExportState.MUXING))

        val result = runBlocking { store.setExportFailed(sessionId) }

        assertTrue(result is Wrote)
        val m = reload()
        assertEquals(ExportState.FAILED, m.exportState)
        assertNull(m.privateTempPath)
        assertNull(m.publicTargetPath)
        assertNull(m.pendingUri)
        assertFalse(m.mediaScanCompleted)
        assertNonExportFieldsPreserved(m)
    }

    @Test
    fun `setExportFailed on unknown sessionId returns UnknownSession`() {
        val result = runBlocking { store.setExportFailed("ghost") }
        assertTrue(result is UnknownSession)
        assertEquals(ExportState.NOT_STARTED, reload().exportState)
    }

    // ─── Phase 6.1b T11 — per-side mutators ─────────────────────────

    @Test
    fun `setExportPendingForSide PORTRAIT writes portraitPendingUri only`() {
        val result = runBlocking {
            store.setExportPendingForSide(
                sessionId,
                com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT,
                "content://media/portrait/77"
            )
        }
        assertTrue(result is Wrote)
        val m = reload()
        assertEquals("content://media/portrait/77", m.portraitPendingUri)
        assertNull(m.landscapePendingUri)
        // Single-mode pointers untouched
        assertNull(m.pendingUri)
        assertNull(m.privateTempPath)
        assertNull(m.publicTargetPath)
        assertFalse(m.mediaScanCompleted)
        assertNonExportFieldsPreserved(m)
    }

    @Test
    fun `setExportPendingForSide LANDSCAPE writes landscapePendingUri only`() {
        val result = runBlocking {
            store.setExportPendingForSide(
                sessionId,
                com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE,
                "content://media/landscape/88"
            )
        }
        assertTrue(result is Wrote)
        val m = reload()
        assertEquals("content://media/landscape/88", m.landscapePendingUri)
        assertNull(m.portraitPendingUri)
        assertNonExportFieldsPreserved(m)
    }

    @Test
    fun `setExportPrivateTargetForSide PORTRAIT writes portraitPrivateTempPath only`() {
        val result = runBlocking {
            store.setExportPrivateTargetForSide(
                sessionId,
                com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT,
                "/tmp/portrait.mp4"
            )
        }
        assertTrue(result is Wrote)
        val m = reload()
        assertEquals("/tmp/portrait.mp4", m.portraitPrivateTempPath)
        assertNull(m.landscapePrivateTempPath)
        assertNull(m.portraitPublicTargetPath)
        assertNull(m.portraitPendingUri)
        assertNonExportFieldsPreserved(m)
    }

    @Test
    fun `setExportFinalizedForSide PORTRAIT clearPrivateTempPath=true clears only portraitPrivateTempPath`() {
        // Pre-state: both sides' private temp paths populated.
        writePreState(
            initial.copy(
                portraitPrivateTempPath = "/tmp/p.mp4",
                landscapePrivateTempPath = "/tmp/l.mp4"
            )
        )
        val result = runBlocking {
            store.setExportFinalizedForSide(
                sessionId,
                com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT,
                publicTargetPath = "/storage/Movies/Rova/portrait.mp4",
                clearPrivateTempPath = true
            )
        }
        assertTrue(result is Wrote)
        val m = reload()
        assertEquals("/storage/Movies/Rova/portrait.mp4", m.portraitPublicTargetPath)
        assertNull(m.portraitPrivateTempPath)
        // Landscape private path untouched.
        assertEquals("/tmp/l.mp4", m.landscapePrivateTempPath)
        assertNull(m.landscapePublicTargetPath)
        assertNonExportFieldsPreserved(m)
    }

    @Test
    fun `setExportFinalizedForSide LANDSCAPE clearPrivateTempPath=false retains landscapePrivateTempPath`() {
        writePreState(initial.copy(landscapePrivateTempPath = "/tmp/l.mp4"))
        val result = runBlocking {
            store.setExportFinalizedForSide(
                sessionId,
                com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE,
                publicTargetPath = "/storage/Movies/Rova/landscape.mp4",
                clearPrivateTempPath = false
            )
        }
        assertTrue(result is Wrote)
        val m = reload()
        assertEquals("/storage/Movies/Rova/landscape.mp4", m.landscapePublicTargetPath)
        assertEquals("/tmp/l.mp4", m.landscapePrivateTempPath)
        assertNonExportFieldsPreserved(m)
    }

    @Test
    fun `setMediaScanCompletedForSide PORTRAIT flips only portraitMediaScanCompleted`() {
        val result = runBlocking {
            store.setMediaScanCompletedForSide(
                sessionId,
                com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT
            )
        }
        assertTrue(result is Wrote)
        val m = reload()
        assertTrue(m.portraitMediaScanCompleted)
        assertFalse(m.landscapeMediaScanCompleted)
        assertFalse(m.mediaScanCompleted)
        assertNonExportFieldsPreserved(m)
    }

    @Test
    fun `per-side mutators on unknown sessionId return UnknownSession`() {
        val result = runBlocking {
            store.setExportPendingForSide(
                "ghost",
                com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT,
                "content://x"
            )
        }
        assertTrue(result is UnknownSession)
        // Real session unchanged.
        val m = reload()
        assertNull(m.portraitPendingUri)
        assertNull(m.landscapePendingUri)
    }

    // ─── Cross-cutting ──────────────────────────────────────────────

    @Test
    fun `Wrote result carries the same manifest state that subsequent loadManifest returns`() {
        val result = runBlocking {
            store.setExportPending(sessionId, "content://media/external/video/99")
        } as Wrote
        val onDisk = reload()
        assertEquals(result.updated.exportState, onDisk.exportState)
        assertEquals(result.updated.pendingUri, onDisk.pendingUri)
        assertEquals(result.updated.privateTempPath, onDisk.privateTempPath)
        assertEquals(result.updated.publicTargetPath, onDisk.publicTargetPath)
        assertEquals(result.updated.mediaScanCompleted, onDisk.mediaScanCompleted)
    }

    @Test
    fun `mutators do not interfere across sessions`() {
        val secondSession = store.createSession(
            config = SessionConfig(
                durationSeconds = 60,
                intervalMinutes = 10,
                resolution = "HD",
                loopCount = 2
            )
        )
        runBlocking {
            store.setExportPending(sessionId, "content://media/A")
            store.setExportPrivateTarget(
                secondSession.sessionId,
                "/tmp/B.mp4",
                "/storage/Movies/Rova/B.mp4"
            )
        }
        val a = requireNotNull(store.loadManifest(sessionId))
        val b = requireNotNull(store.loadManifest(secondSession.sessionId))
        assertEquals("content://media/A", a.pendingUri)
        assertNull(a.privateTempPath)
        assertNull(b.pendingUri)
        assertEquals("/tmp/B.mp4", b.privateTempPath)
        assertEquals("/storage/Movies/Rova/B.mp4", b.publicTargetPath)
    }

    /**
     * Asserts that the non-export portion of the manifest survives any
     * export mutator. Centralized so a future field addition only needs
     * one update here, not per-test.
     */
    private fun assertNonExportFieldsPreserved(after: SessionManifest) {
        assertEquals(initial.exportTier, after.exportTier)
        assertEquals(initial.startedAt, after.startedAt)
        assertEquals(initial.audioMode, after.audioMode)
        assertEquals(initial.config, after.config)
        assertEquals(initial.segments, after.segments)
        assertNull(after.terminated)
        assertNull(after.terminatedAt)
        assertEquals(StopReason.NONE, after.stopReason)
        assertFalse(after.stopRequested)
        assertEquals(initial.sessionId, after.sessionId)
        assertNotNull(after.exportTier)
    }
}
