package com.aritr.rova.ui.screens.player

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SegmentRecord
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.Terminated
import com.aritr.rova.data.VaultState
import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * player-sharing.html §03/§04/§05/§10 — pins the pure Share target decision.
 * JVM-only (no Android): [PlayerSharePlan.resolve] is a total function of the
 * manifest. Mirrors the fixture style of [HistoryArtifactMapperTest].
 *
 * These pins are the safety net for the two invariants a review will attack:
 * (1) vault is NEVER shareable (§10), and (2) the reviewed side is transported,
 * never reconstructed (§04). The full Android URI resolution + chooser launch
 * is device-verified (Robolectric is excluded per the package testing strategy).
 */
class PlayerSharePlanTest {

    private fun manifest(
        tier: ExportTier = ExportTier.TIER1_API29_PLUS,
        exportState: ExportState = ExportState.FINALIZED,
        pendingUri: String? = null,
        publicTargetPath: String? = null,
        safTargetDocUri: String? = null,
        captureTopology: String = "Single",
        portraitPendingUri: String? = null,
        landscapePendingUri: String? = null,
        portraitPublicTargetPath: String? = null,
        landscapePublicTargetPath: String? = null,
        portraitSafTargetDocUri: String? = null,
        landscapeSafTargetDocUri: String? = null,
        terminated: Terminated? = null,
        segments: List<SegmentRecord> = emptyList(),
        vaultState: VaultState = VaultState.PUBLIC,
    ) = SessionManifest(
        sessionId = "s",
        startedAt = 0L,
        config = SessionConfig(30, 5, "FHD", 4, captureTopology = captureTopology),
        segments = segments,
        exportTier = tier,
        exportState = exportState,
        pendingUri = pendingUri,
        publicTargetPath = publicTargetPath,
        safTargetDocUri = safTargetDocUri,
        portraitPendingUri = portraitPendingUri,
        landscapePendingUri = landscapePendingUri,
        portraitPublicTargetPath = portraitPublicTargetPath,
        landscapePublicTargetPath = landscapePublicTargetPath,
        portraitSafTargetDocUri = portraitSafTargetDocUri,
        landscapeSafTargetDocUri = landscapeSafTargetDocUri,
        terminated = terminated,
        vaultState = vaultState,
    )

    private fun seg(name: String) = SegmentRecord(name, 1000L, 100L, "sha-$name")

    // ── null / vault — §10 hard line ─────────────────────────────────────────

    @Test fun nullManifest_isUnavailable() {
        assertEquals(PlayerSharePlan.Unavailable, PlayerSharePlan.resolve(null, null, null))
    }

    @Test fun vault_isNeverShareable_evenWithFinalizedArtifact() {
        // §10 — a VAULTED session has a finalized merged file, yet Share is absent.
        val m = manifest(pendingUri = "content://media/1", vaultState = VaultState.VAULTED)
        assertEquals(PlayerSharePlan.Unavailable, PlayerSharePlan.resolve(m, null, null))
    }

    @Test fun vault_dualShot_isNeverShareable() {
        val m = manifest(
            captureTopology = "DualShot",
            portraitPendingUri = "content://p",
            landscapePendingUri = "content://l",
            vaultState = VaultState.VAULTED,
        )
        assertEquals(PlayerSharePlan.Unavailable, PlayerSharePlan.resolve(m, VideoSide.PORTRAIT, null))
    }

    // ── merged single — §03 ──────────────────────────────────────────────────

    @Test fun mergedTier1Finalized_isSingleMerged() {
        val m = manifest(pendingUri = "content://media/1")
        val plan = PlayerSharePlan.resolve(m, null, null)
        assertEquals(PlayerSharePlan.Single(PlayerShareArtifact(side = null, segmentIndex = null)), plan)
    }

    @Test fun mergedNotFinalized_isUnavailable() {
        // §03 "Not yet FINALIZED → slot absent" — a pending row can exist mid-mux.
        val m = manifest(exportState = ExportState.MUXING, pendingUri = "content://media/1")
        assertEquals(PlayerSharePlan.Unavailable, PlayerSharePlan.resolve(m, null, null))
    }

    @Test fun mergedFinalizedButNoUri_isUnavailable() {
        assertEquals(PlayerSharePlan.Unavailable, PlayerSharePlan.resolve(manifest(pendingUri = null), null, null))
    }

    @Test fun mergedSafFinalized_isSingle() {
        val m = manifest(tier = ExportTier.SAF_DESTINATION, safTargetDocUri = "content://doc/1")
        assertTrue(PlayerSharePlan.resolve(m, null, null) is PlayerSharePlan.Single)
    }

    @Test fun mergedTier3Finalized_isSingle() {
        val m = manifest(tier = ExportTier.TIER3_API24_25, publicTargetPath = "/mnt/Movies/Rova/x.mp4")
        assertTrue(PlayerSharePlan.resolve(m, null, null) is PlayerSharePlan.Single)
    }

    // ── kept-raw segment — §03 ───────────────────────────────────────────────

    @Test fun keptRawSegmentInRange_isSingleSegment() {
        val m = manifest(
            exportState = ExportState.FAILED,
            terminated = Terminated.MULTI_SEGMENT_KEPT,
            segments = listOf(seg("0"), seg("1")),
        )
        val plan = PlayerSharePlan.resolve(m, null, segmentIndex = 1)
        assertEquals(PlayerSharePlan.Single(PlayerShareArtifact(side = null, segmentIndex = 1)), plan)
    }

    @Test fun keptRawSegmentOutOfRange_isUnavailable() {
        val m = manifest(terminated = Terminated.MULTI_SEGMENT_KEPT, segments = listOf(seg("0")))
        assertEquals(PlayerSharePlan.Unavailable, PlayerSharePlan.resolve(m, null, segmentIndex = 5))
    }

    @Test fun segmentIndexWithoutKeptTerminal_isUnavailable() {
        // A stray segmentIndex on a non-kept session must not resolve a share.
        val m = manifest(terminated = Terminated.COMPLETED, segments = listOf(seg("0")))
        assertEquals(PlayerSharePlan.Unavailable, PlayerSharePlan.resolve(m, null, segmentIndex = 0))
    }

    // ── DualShot fork — §04 ──────────────────────────────────────────────────

    @Test fun dualBothSides_isDualChoice_reviewedTransported() {
        val m = manifest(
            captureTopology = "DualShot",
            portraitPendingUri = "content://p",
            landscapePendingUri = "content://l",
        )
        val plan = PlayerSharePlan.resolve(m, VideoSide.LANDSCAPE, null)
        assertEquals(
            PlayerSharePlan.DualChoice(
                reviewed = PlayerShareArtifact(VideoSide.LANDSCAPE, null),
                other = PlayerShareArtifact(VideoSide.PORTRAIT, null),
            ),
            plan,
        )
        // "Both" is the reviewed + other pair (ACTION_SEND_MULTIPLE, §04).
        assertEquals(
            listOf(PlayerShareArtifact(VideoSide.LANDSCAPE, null), PlayerShareArtifact(VideoSide.PORTRAIT, null)),
            (plan as PlayerSharePlan.DualChoice).both,
        )
    }

    @Test fun dualOneSideOnly_isSingleThatSide() {
        val m = manifest(captureTopology = "DualShot", portraitPendingUri = "content://p")
        assertEquals(
            PlayerSharePlan.Single(PlayerShareArtifact(VideoSide.PORTRAIT, null)),
            PlayerSharePlan.resolve(m, VideoSide.PORTRAIT, null),
        )
    }

    @Test fun dualNeitherSide_isUnavailable() {
        val m = manifest(captureTopology = "DualShot")
        assertEquals(PlayerSharePlan.Unavailable, PlayerSharePlan.resolve(m, VideoSide.PORTRAIT, null))
    }

    @Test fun dualBothSides_nullReviewedSide_defaultsPortrait() {
        // Defensive — the Library always transports side, but a null must not crash
        // and must default PORTRAIT-first (ADR-0030 §3).
        val m = manifest(
            captureTopology = "DualShot",
            portraitPendingUri = "content://p",
            landscapePendingUri = "content://l",
        )
        val plan = PlayerSharePlan.resolve(m, side = null, segmentIndex = null) as PlayerSharePlan.DualChoice
        assertEquals(VideoSide.PORTRAIT, plan.reviewed.side)
        assertEquals(VideoSide.LANDSCAPE, plan.other.side)
    }

    @Test fun dualSaf_perSideShareable() {
        val m = manifest(
            tier = ExportTier.SAF_DESTINATION,
            captureTopology = "DualShot",
            portraitSafTargetDocUri = "content://doc/p",
            landscapeSafTargetDocUri = "content://doc/l",
        )
        assertTrue(PlayerSharePlan.resolve(m, VideoSide.PORTRAIT, null) is PlayerSharePlan.DualChoice)
    }

    // ── invariants ───────────────────────────────────────────────────────────

    @Test fun other_flipsSide() {
        assertEquals(VideoSide.LANDSCAPE, PlayerSharePlan.other(VideoSide.PORTRAIT))
        assertEquals(VideoSide.PORTRAIT, PlayerSharePlan.other(VideoSide.LANDSCAPE))
    }

    @Test fun artifactCoordinates_areMutuallyExclusive() {
        // merged: both null; kept: side null; dual: segmentIndex null.
        val merged = PlayerSharePlan.resolve(manifest(pendingUri = "content://m"), null, null) as PlayerSharePlan.Single
        assertNull(merged.artifact.side); assertNull(merged.artifact.segmentIndex)

        val kept = PlayerSharePlan.resolve(
            manifest(terminated = Terminated.MULTI_SEGMENT_KEPT, segments = listOf(seg("0"))),
            null, 0,
        ) as PlayerSharePlan.Single
        assertNull(kept.artifact.side); assertEquals(0, kept.artifact.segmentIndex)

        val dual = PlayerSharePlan.resolve(
            manifest(captureTopology = "DualShot", portraitPendingUri = "content://p"),
            VideoSide.PORTRAIT, null,
        ) as PlayerSharePlan.Single
        assertEquals(VideoSide.PORTRAIT, dual.artifact.side); assertNull(dual.artifact.segmentIndex)
    }
}
