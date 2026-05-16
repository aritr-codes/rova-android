package com.aritr.rova.ui.screens

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 1.7 commit-7 NO-GO patch round 2 — pins the manifest-driven
 * History listing contract. Pre-NO-GO [HistoryViewModel] scanned
 * `videos/<sessionId>/` directly off disk, which Phase 1.7's tier
 * exporters render empty post-merge — so finalized exports vanished
 * from the History screen.
 *
 * The test suite is intentionally JVM-only (no Robolectric, no real
 * `ContentResolver`) because [HistoryArtifactMapper] is a pure data
 * transformer over [SessionManifest] values plus a caller-supplied
 * `resolveTier1Uri` callback. Robolectric / instrumentation coverage
 * is deferred to manual smoke (Phase 1.7 §"Smoke checklist").
 */
class HistoryArtifactMapperTest {

    private fun manifest(
        sessionId: String,
        exportState: ExportState,
        tier: ExportTier,
        pendingUri: String? = null,
        publicTargetPath: String? = null,
        mode: String = "Portrait",
        portraitPendingUri: String? = null,
        portraitPublicTargetPath: String? = null,
        landscapePendingUri: String? = null,
        landscapePublicTargetPath: String? = null
    ) = SessionManifest(
        sessionId = sessionId,
        startedAt = 0L,
        config = SessionConfig(30, 5, "FHD", 4, mode = mode),
        segments = emptyList(),
        exportTier = tier,
        exportState = exportState,
        pendingUri = pendingUri,
        publicTargetPath = publicTargetPath,
        portraitPendingUri = portraitPendingUri,
        portraitPublicTargetPath = portraitPublicTargetPath,
        landscapePendingUri = landscapePendingUri,
        landscapePublicTargetPath = landscapePublicTargetPath
    )

    // ─── finalizedManifests ────────────────────────────────────────

    @Test
    fun `finalizedManifests keeps only FINALIZED`() {
        val all = listOf(
            manifest("a", ExportState.NOT_STARTED, ExportTier.TIER1_API29_PLUS),
            manifest("b", ExportState.MUXING, ExportTier.TIER1_API29_PLUS),
            manifest("c", ExportState.COPYING, ExportTier.TIER2_API26_28),
            manifest(
                "d", ExportState.FINALIZED, ExportTier.TIER1_API29_PLUS,
                pendingUri = "content://media/123"
            ),
            manifest(
                "e", ExportState.FINALIZED, ExportTier.TIER3_API24_25,
                publicTargetPath = "/storage/Movies/Rova/Rova_e.mp4"
            ),
            manifest("f", ExportState.FAILED, ExportTier.TIER2_API26_28)
        )

        val finalized = HistoryArtifactMapper.finalizedManifests(all)

        assertEquals(2, finalized.size)
        assertEquals(setOf("d", "e"), finalized.map { it.sessionId }.toSet())
    }

    @Test
    fun `finalizedManifests does NOT gate on terminated`() {
        // Per ADR 0006 §"Terminal-Write Ordering" + Phase 1.7 commit-6
        // late-terminal pass: a session can be FINALIZED with
        // terminated=null for one cold-launch tick before the runner
        // writes markTerminated(COMPLETED). The History screen must
        // surface the artifact regardless — the user can play the file;
        // the terminal record catches up out-of-band.
        val finalizedNoTerminated = manifest(
            "x", ExportState.FINALIZED, ExportTier.TIER1_API29_PLUS,
            pendingUri = "content://media/x"
        )
        assertNull(finalizedNoTerminated.terminated)

        val finalized = HistoryArtifactMapper.finalizedManifests(listOf(finalizedNoTerminated))

        assertEquals(1, finalized.size)
        assertEquals("x", finalized.single().sessionId)
    }

    // ─── resolveArtifactFile ───────────────────────────────────────

    @Test
    fun `Tier 1 resolves via pendingUri callback`() {
        val expected = File("/storage/Movies/Rova/Rova_t1.mp4")
        val m = manifest(
            "t1", ExportState.FINALIZED, ExportTier.TIER1_API29_PLUS,
            pendingUri = "content://media/external/video/media/42"
        )

        var seenUri: String? = null
        val resolved = HistoryArtifactMapper.resolveArtifactFile(m) { uri ->
            seenUri = uri
            expected
        }

        assertEquals("content://media/external/video/media/42", seenUri)
        assertEquals(expected, resolved)
    }

    @Test
    fun `Tier 1 with null pendingUri returns null without invoking callback`() {
        val m = manifest("t1n", ExportState.FINALIZED, ExportTier.TIER1_API29_PLUS, pendingUri = null)
        var invoked = false
        val resolved = HistoryArtifactMapper.resolveArtifactFile(m) {
            invoked = true
            File("/should/not/be/used")
        }
        assertNull(resolved)
        assertTrue("resolver must not be invoked when pendingUri is null", !invoked)
    }

    @Test
    fun `Tier 1 with unresolvable URI returns null`() {
        val m = manifest(
            "t1u", ExportState.FINALIZED, ExportTier.TIER1_API29_PLUS,
            pendingUri = "content://media/external/video/media/9999"
        )
        val resolved = HistoryArtifactMapper.resolveArtifactFile(m) { null }
        assertNull(resolved)
    }

    @Test
    fun `Tier 2 resolves via publicTargetPath`() {
        val m = manifest(
            "t2", ExportState.FINALIZED, ExportTier.TIER2_API26_28,
            publicTargetPath = "/storage/Movies/Rova/Rova_t2.mp4"
        )
        val resolved = HistoryArtifactMapper.resolveArtifactFile(m) { error("Tier 1 callback must not fire for Tier 2") }
        assertEquals(File("/storage/Movies/Rova/Rova_t2.mp4"), resolved)
    }

    @Test
    fun `Tier 3 resolves via publicTargetPath`() {
        val m = manifest(
            "t3", ExportState.FINALIZED, ExportTier.TIER3_API24_25,
            publicTargetPath = "/storage/Movies/Rova/Rova_t3.mp4"
        )
        val resolved = HistoryArtifactMapper.resolveArtifactFile(m) { error("Tier 1 callback must not fire for Tier 3") }
        assertEquals(File("/storage/Movies/Rova/Rova_t3.mp4"), resolved)
    }

    @Test
    fun `Tier 2 with null publicTargetPath returns null`() {
        val m = manifest("t2n", ExportState.FINALIZED, ExportTier.TIER2_API26_28, publicTargetPath = null)
        val resolved = HistoryArtifactMapper.resolveArtifactFile(m) { error("Tier 1 callback must not fire for Tier 2") }
        assertNull(resolved)
    }

    @Test
    fun `Tier 3 with null publicTargetPath returns null`() {
        val m = manifest("t3n", ExportState.FINALIZED, ExportTier.TIER3_API24_25, publicTargetPath = null)
        val resolved = HistoryArtifactMapper.resolveArtifactFile(m) { error("Tier 1 callback must not fire for Tier 3") }
        assertNull(resolved)
    }

    // ─── resolveShareUri ───────────────────────────────────────────

    @Test
    fun `Tier 1 share URI passes through pendingUri`() {
        val m = manifest(
            "t1s", ExportState.FINALIZED, ExportTier.TIER1_API29_PLUS,
            pendingUri = "content://media/external/video/media/77"
        )
        assertEquals("content://media/external/video/media/77", HistoryArtifactMapper.resolveShareUri(m))
    }

    @Test
    fun `Tier 1 share URI is null when pendingUri is null`() {
        val m = manifest("t1sn", ExportState.FINALIZED, ExportTier.TIER1_API29_PLUS, pendingUri = null)
        assertNull(HistoryArtifactMapper.resolveShareUri(m))
    }

    @Test
    fun `Tier 2 share URI is null at mapper layer`() {
        // Tier 2/3 manifests persist only `publicTargetPath`; the share
        // URI must be looked up by `_DATA` against MediaStore, which is
        // a ContentResolver concern and lives in the ViewModel.
        val m = manifest(
            "t2s", ExportState.FINALIZED, ExportTier.TIER2_API26_28,
            publicTargetPath = "/storage/Movies/Rova/Rova_t2s.mp4"
        )
        assertNull(HistoryArtifactMapper.resolveShareUri(m))
    }

    @Test
    fun `Tier 3 share URI is null at mapper layer`() {
        val m = manifest(
            "t3s", ExportState.FINALIZED, ExportTier.TIER3_API24_25,
            publicTargetPath = "/storage/Movies/Rova/Rova_t3s.mp4"
        )
        assertNull(HistoryArtifactMapper.resolveShareUri(m))
    }

    // ─── End-to-end (filter + tier dispatch) ───────────────────────

    @Test
    fun `manifest list with mixed tiers and states maps correctly`() {
        // Pin the regression: a session that was FINALIZED on Tier 1
        // (gallery insert) must surface a File when its URI resolves —
        // pre-NO-GO logic produced no File at all.
        val all = listOf(
            // Should appear (Tier 1, FINALIZED, URI resolves)
            manifest(
                "q", ExportState.FINALIZED, ExportTier.TIER1_API29_PLUS,
                pendingUri = "content://media/q"
            ),
            // Should appear (Tier 2, FINALIZED, path set)
            manifest(
                "r", ExportState.FINALIZED, ExportTier.TIER2_API26_28,
                publicTargetPath = "/storage/Movies/Rova/Rova_r.mp4"
            ),
            // Should appear (Tier 3, FINALIZED, path set)
            manifest(
                "s", ExportState.FINALIZED, ExportTier.TIER3_API24_25,
                publicTargetPath = "/storage/Movies/Rova/Rova_s.mp4"
            ),
            // Should NOT appear (FAILED)
            manifest(
                "x", ExportState.FAILED, ExportTier.TIER1_API29_PLUS,
                pendingUri = "content://media/x"
            ),
            // Should NOT appear (Tier 1, FINALIZED but URI unresolvable)
            manifest(
                "y", ExportState.FINALIZED, ExportTier.TIER1_API29_PLUS,
                pendingUri = "content://media/y"
            )
        )
        val resolveTier1: (String) -> File? = { uri ->
            when (uri) {
                "content://media/q" -> File("/storage/Movies/Rova/Rova_q.mp4")
                else -> null
            }
        }

        val files = HistoryArtifactMapper.finalizedManifests(all)
            .mapNotNull { HistoryArtifactMapper.resolveArtifactFile(it, resolveTier1) }
            .map { it.absolutePath }
            .toSet()

        // Use endsWith because File.absolutePath on Windows test JVM
        // prefixes with the drive letter via Path normalization.
        assertEquals(3, files.size)
        assertTrue(files.any { it.endsWith("Rova_q.mp4") })
        assertTrue(files.any { it.endsWith("Rova_r.mp4") })
        assertTrue(files.any { it.endsWith("Rova_s.mp4") })
    }

    // ─── Phase 6.1b T16 — resolveArtifactsPerSide (P+L fanout) ────

    @Test
    fun `Tier 2 P+L both sides emits 2 PORTRAIT plus LANDSCAPE artifacts`() {
        val m = manifest(
            "pl", ExportState.FINALIZED, ExportTier.TIER2_API26_28,
            mode = "PortraitLandscape",
            portraitPublicTargetPath = "/storage/Movies/Rova/Rova_pl_portrait.mp4",
            landscapePublicTargetPath = "/storage/Movies/Rova/Rova_pl_landscape.mp4"
        )

        val artifacts = HistoryArtifactMapper.resolveArtifactsPerSide(m) { uri ->
            error("Tier 1 callback must not fire for Tier 2 P+L: uri=$uri")
        }

        assertEquals(2, artifacts.size)
        val portrait = artifacts.single { it.side == VideoSide.PORTRAIT }
        val landscape = artifacts.single { it.side == VideoSide.LANDSCAPE }
        assertEquals(File("/storage/Movies/Rova/Rova_pl_portrait.mp4"), portrait.file)
        assertEquals(File("/storage/Movies/Rova/Rova_pl_landscape.mp4"), landscape.file)
        // Tier 2 carries no per-side share URI at the mapper layer; caller
        // looks it up by _DATA against MediaStore (same contract as
        // single-mode resolveShareUri).
        assertNull(portrait.shareUri)
        assertNull(landscape.shareUri)
    }

    @Test
    fun `Tier 1 P+L both sides emits 2 artifacts with per-side share URIs`() {
        val m = manifest(
            "pl1", ExportState.FINALIZED, ExportTier.TIER1_API29_PLUS,
            mode = "PortraitLandscape",
            portraitPendingUri = "content://media/external/video/media/100",
            portraitPublicTargetPath = "content://media/external/video/media/100",
            landscapePendingUri = "content://media/external/video/media/200",
            landscapePublicTargetPath = "content://media/external/video/media/200"
        )

        val portraitFile = File("/storage/Movies/Rova/Rova_pl1_portrait.mp4")
        val landscapeFile = File("/storage/Movies/Rova/Rova_pl1_landscape.mp4")
        val seenUris = mutableListOf<String>()
        val artifacts = HistoryArtifactMapper.resolveArtifactsPerSide(m) { uri ->
            seenUris += uri
            when (uri) {
                "content://media/external/video/media/100" -> portraitFile
                "content://media/external/video/media/200" -> landscapeFile
                else -> null
            }
        }

        assertEquals(2, artifacts.size)
        assertEquals(
            setOf(
                "content://media/external/video/media/100",
                "content://media/external/video/media/200"
            ),
            seenUris.toSet()
        )
        val portrait = artifacts.single { it.side == VideoSide.PORTRAIT }
        val landscape = artifacts.single { it.side == VideoSide.LANDSCAPE }
        assertEquals(portraitFile, portrait.file)
        assertEquals(landscapeFile, landscape.file)
        assertEquals("content://media/external/video/media/100", portrait.shareUri)
        assertEquals("content://media/external/video/media/200", landscape.shareUri)
    }

    @Test
    fun `Tier 2 P+L one-sided success emits only the side that succeeded`() {
        val m = manifest(
            "pl1s", ExportState.FINALIZED, ExportTier.TIER2_API26_28,
            mode = "PortraitLandscape",
            portraitPublicTargetPath = "/storage/Movies/Rova/Rova_pl1s_portrait.mp4",
            landscapePublicTargetPath = null
        )

        val artifacts = HistoryArtifactMapper.resolveArtifactsPerSide(m) { error("never") }

        assertEquals(1, artifacts.size)
        assertEquals(VideoSide.PORTRAIT, artifacts.single().side)
        assertEquals(
            File("/storage/Movies/Rova/Rova_pl1s_portrait.mp4"),
            artifacts.single().file
        )
    }

    @Test
    fun `Tier 2 P+L both sides null emits empty`() {
        val m = manifest(
            "pl0", ExportState.FINALIZED, ExportTier.TIER2_API26_28,
            mode = "PortraitLandscape",
            portraitPublicTargetPath = null,
            landscapePublicTargetPath = null
        )

        val artifacts = HistoryArtifactMapper.resolveArtifactsPerSide(m) { error("never") }

        assertTrue("expected empty fanout for P+L with no per-side paths", artifacts.isEmpty())
    }

    @Test
    fun `Tier 1 P+L with unresolvable URI drops that side`() {
        val m = manifest(
            "pl1u", ExportState.FINALIZED, ExportTier.TIER1_API29_PLUS,
            mode = "PortraitLandscape",
            portraitPendingUri = "content://media/external/video/media/300",
            portraitPublicTargetPath = "content://media/external/video/media/300",
            landscapePendingUri = "content://media/external/video/media/9999",
            landscapePublicTargetPath = "content://media/external/video/media/9999"
        )

        val portraitFile = File("/storage/Movies/Rova/Rova_pl1u_portrait.mp4")
        val artifacts = HistoryArtifactMapper.resolveArtifactsPerSide(m) { uri ->
            if (uri == "content://media/external/video/media/300") portraitFile else null
        }

        assertEquals(1, artifacts.size)
        assertEquals(VideoSide.PORTRAIT, artifacts.single().side)
        assertEquals(portraitFile, artifacts.single().file)
    }

    @Test
    fun `single-mode session returns empty per-side fanout`() {
        // Pin the routing invariant: only Portrait+Landscape sessions are
        // eligible for the per-side fanout. Single-mode "Portrait" /
        // "Landscape" manifests must surface via the existing single-card
        // resolveArtifactFile path, not the per-side path. This pins the
        // VM-layer branch: "if mode == PortraitLandscape, call
        // resolveArtifactsPerSide; else call resolveArtifactFile."
        val portraitOnly = manifest(
            "p", ExportState.FINALIZED, ExportTier.TIER2_API26_28,
            mode = "Portrait",
            publicTargetPath = "/storage/Movies/Rova/Rova_p.mp4",
            // Even if a legacy/buggy build wrote per-side fields on a
            // single-mode session, the mapper does not fan them out.
            portraitPublicTargetPath = "/storage/Movies/Rova/Rova_should_not_emit.mp4"
        )

        val artifacts = HistoryArtifactMapper.resolveArtifactsPerSide(portraitOnly) {
            error("never")
        }

        assertTrue("single-mode must not fan out per-side", artifacts.isEmpty())
    }
}
