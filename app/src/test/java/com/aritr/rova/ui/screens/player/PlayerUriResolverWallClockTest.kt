package com.aritr.rova.ui.screens.player

import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SegmentRecord
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerUriResolverWallClockTest {

    private fun cfg(topology: String = "Single") = SessionConfig(
        durationSeconds = 30, intervalSeconds = 900, resolution = "FHD", loopCount = 2,
        captureTopology = topology,
    )

    private fun manifest(segments: List<SegmentRecord>, topology: String = "Single") = SessionManifest(
        sessionId = "s1", startedAt = 1_700_000_000_000L, config = cfg(topology),
        segments = segments, exportTier = ExportTier.TIER2_API26_28,
    ).copy(exportState = ExportState.FINALIZED, publicTargetPath = "/movies/Rova/s1.mp4")

    @Test fun `exact stamps preserved, mask all false`() {
        val t0 = 1_700_000_000_000L
        val m = manifest(listOf(
            SegmentRecord("segment_0001.mp4", 30000L, 1L, "a", startedAtWallClock = t0),
            SegmentRecord("segment_0002.mp4", 30000L, 1L, "b", startedAtWallClock = t0 + 900_000L),
        ))
        val r = PlayerUriResolver.resolve(m) as PlayerUiState.Ready
        assertEquals(listOf(t0, t0 + 900_000L), r.segmentWallStartsMs)
        assertEquals(listOf(false, false), r.wallStartIsApproxMask)
    }

    @Test fun `legacy null stamps synthesize from startedAt, mask all true`() {
        val t0 = 1_700_000_000_000L
        val m = manifest(listOf(
            SegmentRecord("segment_0001.mp4", 30000L, 1L, "a"),
            SegmentRecord("segment_0002.mp4", 30000L, 1L, "b"),
        ))
        val r = PlayerUriResolver.resolve(m) as PlayerUiState.Ready
        assertEquals(listOf(t0, t0 + 30_000L), r.segmentWallStartsMs)   // chain from startedAt
        assertEquals(listOf(true, true), r.wallStartIsApproxMask)
    }

    @Test fun `mixed null — exact preserved, only orphan synthesized (codex #1)`() {
        val t0 = 1_700_000_000_000L
        val m = manifest(listOf(
            SegmentRecord("segment_0001.mp4", 30000L, 1L, "a", startedAtWallClock = t0),
            SegmentRecord("segment_0002.mp4", 30000L, 1L, "b"),  // recovered orphan
        ))
        val r = PlayerUriResolver.resolve(m) as PlayerUiState.Ready
        assertEquals(t0, r.segmentWallStartsMs[0])                       // exact preserved
        assertEquals(t0 + 30_000L, r.segmentWallStartsMs[1])            // synth = prevStart+prevDur
        assertEquals(listOf(false, true), r.wallStartIsApproxMask)
    }

    @Test fun `DualShot orders per-side by sequence, not list order (codex #2)`() {
        val t0 = 1_700_000_000_000L
        // manifest interleaves + out of order: P2, L1, P1, L2
        val m = manifest(listOf(
            SegmentRecord("segment_0002_P.mp4", 30000L, 1L, "p2", VideoSide.PORTRAIT, startedAtWallClock = t0 + 900_000L),
            SegmentRecord("segment_0001_L.mp4", 30000L, 1L, "l1", VideoSide.LANDSCAPE, startedAtWallClock = t0),
            SegmentRecord("segment_0001_P.mp4", 30000L, 1L, "p1", VideoSide.PORTRAIT, startedAtWallClock = t0),
            SegmentRecord("segment_0002_L.mp4", 30000L, 1L, "l2", VideoSide.LANDSCAPE, startedAtWallClock = t0 + 900_000L),
        ), topology = "DualShot").copy(
            portraitPublicTargetPath = "/movies/Rova/s1_P.mp4",
            landscapePublicTargetPath = "/movies/Rova/s1_L.mp4",
        )
        val r = PlayerUriResolver.resolve(m, VideoSide.PORTRAIT) as PlayerUiState.Ready
        assertEquals(listOf(t0, t0 + 900_000L), r.segmentWallStartsMs)  // P1 then P2
        assertEquals(2, r.segmentDurationsMs.size)
    }

    @Test fun `single-mode order unchanged for in-order list`() {
        val t0 = 1_700_000_000_000L
        val m = manifest(listOf(
            SegmentRecord("segment_0001.mp4", 10000L, 1L, "a", startedAtWallClock = t0),
            SegmentRecord("segment_0002.mp4", 20000L, 1L, "b", startedAtWallClock = t0 + 900_000L),
        ))
        val r = PlayerUriResolver.resolve(m) as PlayerUiState.Ready
        assertEquals(listOf(10000L, 20000L), r.segmentDurationsMs)
    }
}
