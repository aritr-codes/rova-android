package com.aritr.rova.ui.screens.player

import com.aritr.rova.R
import com.aritr.rova.data.AudioMode
import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SegmentRecord
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * player-info.html §03/§05/§06/§07 — pins the pure Info model. JVM-only:
 * [PlayerInfoModel.build] and [PlayerInfoModel.provenanceOf] are total functions
 * of the manifest + Ready. Mirrors [PlayerSharePlanTest]'s fixture style.
 *
 * The safety net for the invariants a review will attack: (1) provenance maps
 * only REAL enums, null → neutral fallback (§05); (2) fps/codec/bitrate never
 * appear — only requestedQuality (§04); (3) DualShot reads existing per-side
 * fields, never reconstructs identity (§06); (4) kept-raw reports what was kept
 * + "not created" (§07). The Compose rendering is device-verified.
 */
class PlayerInfoModelTest {

    private fun manifest(
        tier: ExportTier = ExportTier.TIER1_API29_PLUS,
        exportState: ExportState = ExportState.FINALIZED,
        pendingUri: String? = "content://media/1",
        publicTargetPath: String? = null,
        safTargetDocUri: String? = null,
        captureTopology: String = "Single",
        orientationPolicy: String = "FollowDevice",
        orientationLockRotation: Int = -1,
        resolution: String = "FHD",
        audioMode: AudioMode = AudioMode.VIDEO_AUDIO,
        intervalSeconds: Int = 30,
        loopCount: Int = 3,
        terminated: Terminated? = Terminated.COMPLETED,
        stopReason: StopReason = StopReason.NONE,
        segments: List<SegmentRecord> = listOf(seg("0", 1000), seg("1", 2000)),
        portraitPendingUri: String? = null,
        landscapePendingUri: String? = null,
    ) = SessionManifest(
        sessionId = "s",
        startedAt = 1000L,
        config = SessionConfig(
            durationSeconds = 30,
            intervalSeconds = intervalSeconds,
            resolution = resolution,
            loopCount = loopCount,
            captureTopology = captureTopology,
            orientationPolicy = orientationPolicy,
            orientationLockRotation = orientationLockRotation,
        ),
        segments = segments,
        exportTier = tier,
        exportState = exportState,
        pendingUri = pendingUri,
        publicTargetPath = publicTargetPath,
        safTargetDocUri = safTargetDocUri,
        audioMode = audioMode,
        terminated = terminated,
        stopReason = stopReason,
        portraitPendingUri = portraitPendingUri,
        landscapePendingUri = landscapePendingUri,
    )

    private fun seg(name: String, bytes: Long, side: VideoSide? = null) =
        SegmentRecord(name, 1000L, bytes, "sha-$name", side = side)

    private fun ready(startedAt: Long = 1000L, totalMs: Long = 80_000L, clips: Int = 3) =
        PlayerUiState.Ready(
            mediaUri = "content://x",
            sessionId = "s",
            startedAt = startedAt,
            segmentDurationsMs = emptyList(),
            perClipDurationMs = 30_000L,
            totalClips = clips,
            totalDurationFromSegmentsMs = totalMs,
            segmentWallStartsMs = emptyList(),
            wallStartIsApproxMask = emptyList(),
        )

    // ── provenance mapping — §05 ─────────────────────────────────────────────

    @Test fun provenance_completed() {
        val p = PlayerInfoModel.provenanceOf(Terminated.COMPLETED, StopReason.NONE)
        assertEquals(R.string.player_info_prov_completed, p.bannerRes)
        assertEquals(R.string.player_info_detail_completed, p.detailRes)
        assertFalse(p.warn)
    }

    @Test fun provenance_userStopped_isNeutral() {
        val p = PlayerInfoModel.provenanceOf(Terminated.USER_STOPPED, StopReason.USER)
        assertEquals(R.string.player_info_prov_user_stopped, p.bannerRes)
        assertEquals(R.string.player_info_detail_user, p.detailRes)
        assertFalse(p.warn)
    }

    @Test fun provenance_systemThermal_mapsReason_andWarns() {
        val p = PlayerInfoModel.provenanceOf(Terminated.KILLED_BY_SYSTEM, StopReason.THERMAL)
        assertEquals(R.string.player_info_prov_system, p.bannerRes)
        assertEquals(R.string.player_info_reason_thermal, p.detailRes)
        assertTrue(p.warn)
    }

    @Test fun provenance_systemLowStorage_mapsReason() {
        val p = PlayerInfoModel.provenanceOf(Terminated.KILLED_BY_SYSTEM, StopReason.LOW_STORAGE)
        assertEquals(R.string.player_info_reason_low_storage, p.detailRes)
    }

    @Test fun provenance_systemNoReason_hasNoDetail() {
        // §05 — StopReason.NONE on a system kill yields no detail line.
        val p = PlayerInfoModel.provenanceOf(Terminated.KILLED_BY_SYSTEM, StopReason.NONE)
        assertNull(p.detailRes)
        assertTrue(p.warn)
    }

    @Test fun provenance_forceStop_warns() {
        val p = PlayerInfoModel.provenanceOf(Terminated.KILLED_FORCE_STOP, StopReason.NONE)
        assertEquals(R.string.player_info_prov_force_stop, p.bannerRes)
        assertTrue(p.warn)
    }

    @Test fun provenance_kept_warns() {
        val p = PlayerInfoModel.provenanceOf(Terminated.MULTI_SEGMENT_KEPT, StopReason.NONE)
        assertEquals(R.string.player_info_prov_kept, p.bannerRes)
        assertEquals(R.string.player_info_detail_kept, p.detailRes)
        assertTrue(p.warn)
    }

    @Test fun provenance_null_fallsBackNeutral() {
        // §05 — the player never opens a live session; null → neutral, no detail.
        val p = PlayerInfoModel.provenanceOf(null, StopReason.NONE)
        assertEquals(R.string.player_info_prov_finished, p.bannerRes)
        assertNull(p.detailRes)
        assertFalse(p.warn)
    }

    // ── build: single ────────────────────────────────────────────────────────

    @Test fun build_single_basicsAndCapture() {
        val m = manifest(segments = listOf(seg("0", 1000), seg("1", 2000)))
        val model = PlayerInfoModel.build(m, ready(totalMs = 80_000L, clips = 3), reviewedSide = null)
        assertEquals(PlayerInfoModel.Topology.SINGLE, model.topology)
        assertNull(model.angles)
        assertFalse(model.keptRaw)
        assertEquals(80_000L, model.durationMs)
        assertEquals(3, model.clips)
        assertEquals(3000L, model.totalSizeBytes)
        assertEquals("FHD", model.requestedQuality)
        assertTrue(model.audioOn)
        assertTrue(model.hasMergedOutput)
    }

    @Test fun build_audioOff_whenVideoOnly() {
        val model = PlayerInfoModel.build(manifest(audioMode = AudioMode.VIDEO_ONLY), ready(), null)
        assertFalse(model.audioOn)
    }

    @Test fun build_orientation_followDevice() {
        val model = PlayerInfoModel.build(manifest(orientationPolicy = "FollowDevice"), ready(), null)
        assertEquals(PlayerInfoModel.Orientation.FOLLOW, model.orientation)
    }

    @Test fun build_orientation_lockPortrait() {
        val model = PlayerInfoModel.build(
            manifest(orientationPolicy = "Lock", orientationLockRotation = 0), ready(), null,
        )
        assertEquals(PlayerInfoModel.Orientation.PORTRAIT, model.orientation)
    }

    @Test fun build_orientation_lockLandscape() {
        val model = PlayerInfoModel.build(
            manifest(orientationPolicy = "Lock", orientationLockRotation = 1), ready(), null,
        )
        assertEquals(PlayerInfoModel.Orientation.LANDSCAPE, model.orientation)
    }

    // ── build: kept-raw — §07 ────────────────────────────────────────────────

    @Test fun build_keptRaw_flagsAndNoMergedOutput() {
        val m = manifest(
            exportState = ExportState.FAILED,
            pendingUri = null,
            terminated = Terminated.MULTI_SEGMENT_KEPT,
            segments = listOf(seg("0", 500), seg("1", 500)),
        )
        val model = PlayerInfoModel.build(m, ready(totalMs = 40_000L, clips = 2), null)
        assertTrue(model.keptRaw)
        assertFalse(model.hasMergedOutput) // → "Combined file · not created" row shows
        assertEquals(1000L, model.totalSizeBytes)
    }

    // ── build: DualShot — §06 ────────────────────────────────────────────────

    @Test fun build_dual_describesBothSides_reviewedTransported() {
        val m = manifest(
            captureTopology = "DualShot",
            pendingUri = null,
            portraitPendingUri = "content://p",
            landscapePendingUri = "content://l",
            segments = listOf(
                seg("p0", 400, VideoSide.PORTRAIT),
                seg("l0", 300, VideoSide.LANDSCAPE),
                seg("p1", 400, VideoSide.PORTRAIT),
            ),
        )
        val model = PlayerInfoModel.build(m, ready(), reviewedSide = VideoSide.LANDSCAPE)
        assertEquals(PlayerInfoModel.Topology.DUAL, model.topology)
        val a = model.angles!!
        assertEquals(VideoSide.LANDSCAPE, a.reviewedSide) // transported, not reconstructed
        assertEquals(800L, a.portraitBytes)
        assertEquals(300L, a.landscapeBytes)
        assertTrue(a.portraitFinalized)
        assertTrue(a.landscapeFinalized)
        assertEquals(1100L, model.totalSizeBytes) // §01 "Size · total" = both sides
    }

    @Test fun build_dual_nullReviewed_defaultsPortrait() {
        val m = manifest(
            captureTopology = "DualShot",
            portraitPendingUri = "content://p",
            landscapePendingUri = "content://l",
            segments = listOf(seg("p", 100, VideoSide.PORTRAIT), seg("l", 100, VideoSide.LANDSCAPE)),
        )
        val model = PlayerInfoModel.build(m, ready(), reviewedSide = null)
        assertEquals(VideoSide.PORTRAIT, model.angles!!.reviewedSide)
    }

    @Test fun build_dual_oneSideNotFinalized_isHonest() {
        // §06 — a side with no per-side pointer shows the honest "not finalized" state.
        val m = manifest(
            captureTopology = "DualShot",
            portraitPendingUri = "content://p",
            landscapePendingUri = null,
            segments = listOf(seg("p", 100, VideoSide.PORTRAIT)),
        )
        val model = PlayerInfoModel.build(m, ready(), reviewedSide = VideoSide.PORTRAIT)
        assertTrue(model.angles!!.portraitFinalized)
        assertFalse(model.angles!!.landscapeFinalized)
    }

    // ── humanDuration ────────────────────────────────────────────────────────

    @Test fun humanDuration_shapes() {
        assertEquals("1m 20s", PlayerInfoModel.humanDuration(80_000L))
        assertEquals("40s", PlayerInfoModel.humanDuration(40_000L))
        assertEquals("2m", PlayerInfoModel.humanDuration(120_000L))
        assertEquals("0s", PlayerInfoModel.humanDuration(0L))
        assertEquals("0s", PlayerInfoModel.humanDuration(-5L))
    }
}
