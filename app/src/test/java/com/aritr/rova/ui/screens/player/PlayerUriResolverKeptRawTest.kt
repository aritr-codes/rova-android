package com.aritr.rova.ui.screens.player

import com.aritr.rova.R
import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SegmentRecord
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0037 §5 validity matrix — kept-raw (V2) + rejections (V4/V4b/V5).
 * V1/V3 regression is the three pre-existing PlayerUriResolver*Test suites,
 * which must pass unchanged.
 */
class PlayerUriResolverKeptRawTest {

    private fun keptRawManifest(
        sessionId: String = "kr1",
        exportState: ExportState = ExportState.NOT_STARTED,
        topology: String = "Single",
        segments: List<SegmentRecord>,
        startedAt: Long = 1_715_000_000_000L,
        terminated: Terminated? = Terminated.MULTI_SEGMENT_KEPT
    ) = SessionManifest(
        sessionId = sessionId,
        startedAt = startedAt,
        config = SessionConfig(
            durationSeconds = 30,
            intervalSeconds = 0,
            resolution = "FHD",
            loopCount = segments.size.coerceAtLeast(1),
            captureTopology = topology
        ),
        segments = segments,
        exportTier = ExportTier.TIER1_API29_PLUS,
        exportState = exportState,
        terminated = terminated
    )

    private fun seg(filename: String, durationMs: Long, side: VideoSide? = null, startedAtWallClock: Long? = null) =
        SegmentRecord(
            filename = filename,
            durationMs = durationMs,
            sizeBytes = 1L,
            sha1 = "0",
            side = side,
            startedAtWallClock = startedAtWallClock
        )

    // V2: user-keep writer path (exportState = NOT_STARTED)
    @Test fun `kept-raw segment resolves Ready with sentinel scheme and single-clip timeline`() {
        val m = keptRawManifest(
            exportState = ExportState.NOT_STARTED,
            segments = listOf(seg("segment_0000.mp4", 30_000L), seg("segment_0001.mp4", 31_000L))
        )
        val r = PlayerUriResolver.resolve(m, side = null, segmentIndex = 1) as PlayerUiState.Ready
        assertEquals(PlayerUriResolver.KEPT_SEGMENT_SCHEME + "segment_0001.mp4", r.mediaUri)
        assertEquals(1, r.totalClips)
        assertEquals(listOf(31_000L), r.segmentDurationsMs)
        assertEquals(31_000L, r.totalDurationFromSegmentsMs)
    }

    // V2: classifier writer path (exportState = FAILED) must also resolve
    @Test fun `kept-raw resolves Ready when exportState is FAILED`() {
        val m = keptRawManifest(
            exportState = ExportState.FAILED,
            segments = listOf(seg("segment_0000.mp4", 30_000L), seg("segment_0001.mp4", 31_000L))
        )
        val r = PlayerUriResolver.resolve(m, side = null, segmentIndex = 1) as PlayerUiState.Ready
        assertEquals(PlayerUriResolver.KEPT_SEGMENT_SCHEME + "segment_0001.mp4", r.mediaUri)
    }

    // V2 wall-clock: exact stamp when present; synthesized (approx) when null
    @Test fun `kept-raw wall start is the segment's own stamp exact`() {
        val m = keptRawManifest(
            segments = listOf(
                seg("segment_0000.mp4", 30_000L),
                seg("segment_0001.mp4", 31_000L, startedAtWallClock = 1_700_000_000_000L)
            )
        )
        val r = PlayerUriResolver.resolve(m, side = null, segmentIndex = 1) as PlayerUiState.Ready
        assertEquals(listOf(1_700_000_000_000L), r.segmentWallStartsMs)
        assertEquals(listOf(false), r.wallStartIsApproxMask)
    }

    @Test fun `kept-raw wall start synthesizes from manifest startedAt when stamp missing`() {
        val m = keptRawManifest(
            startedAt = 1_000_000_000_000L,
            segments = listOf(
                seg("segment_0000.mp4", 30_000L),
                seg("segment_0001.mp4", 31_000L),
                seg("segment_0002.mp4", 20_000L)
            )
        )
        val r = PlayerUriResolver.resolve(m, side = null, segmentIndex = 2) as PlayerUiState.Ready
        assertEquals(listOf(1_000_000_000_000L + 30_000L + 31_000L), r.segmentWallStartsMs)
        assertEquals(listOf(true), r.wallStartIsApproxMask)
    }

    // V2: DualShot kept-raw — index into the FULL interleaved array; record's own side irrelevant to input
    @Test fun `kept-raw DualShot segment resolves by full-array index with side null`() {
        val m = keptRawManifest(topology = "DualShot", segments = listOf(
            seg("segment_0000_P.mp4", 30_000L, side = VideoSide.PORTRAIT),
            seg("segment_0000_L.mp4", 30_000L, side = VideoSide.LANDSCAPE)
        ))
        val r = PlayerUriResolver.resolve(m, side = null, segmentIndex = 1) as PlayerUiState.Ready
        assertEquals(PlayerUriResolver.KEPT_SEGMENT_SCHEME + "segment_0000_L.mp4", r.mediaUri)
    }

    // V4: segmentIndex against a non-KEPT manifest (incl. FINALIZED) → invalid identity
    @Test fun `segmentIndex on FINALIZED session is rejected`() {
        val m = keptRawManifest(
            exportState = ExportState.FINALIZED,
            terminated = null,
            segments = listOf(seg("segment_0000.mp4", 30_000L))
        )
        val r = PlayerUriResolver.resolve(m, side = null, segmentIndex = 0)
        assertTrue("expected Unavailable, got $r", r is PlayerUiState.Unavailable)
        assertEquals(
            UiText.Str(R.string.player_unavailable_not_available),
            (r as PlayerUiState.Unavailable).reason
        )
    }

    // V4b: both coordinates → malformed
    @Test fun `side plus segmentIndex is rejected as malformed`() {
        val m = keptRawManifest(segments = listOf(seg("segment_0000.mp4", 30_000L)))
        val r = PlayerUriResolver.resolve(m, side = VideoSide.PORTRAIT, segmentIndex = 0)
        assertTrue("expected Unavailable, got $r", r is PlayerUiState.Unavailable)
        assertEquals(
            UiText.Str(R.string.player_unavailable_not_available),
            (r as PlayerUiState.Unavailable).reason
        )
    }

    // V5: out-of-range index → fail closed
    @Test fun `out-of-range segmentIndex is unavailable`() {
        val m = keptRawManifest(segments = listOf(
            seg("segment_0000.mp4", 30_000L), seg("segment_0001.mp4", 31_000L)
        ))
        val rHigh = PlayerUriResolver.resolve(m, side = null, segmentIndex = 5)
        assertTrue("expected Unavailable, got $rHigh", rHigh is PlayerUiState.Unavailable)
        assertEquals(
            UiText.Str(R.string.player_unavailable_file_not_found),
            (rHigh as PlayerUiState.Unavailable).reason
        )
        val rNeg = PlayerUriResolver.resolve(m, side = null, segmentIndex = -1)
        assertTrue("expected Unavailable, got $rNeg", rNeg is PlayerUiState.Unavailable)
        assertEquals(
            UiText.Str(R.string.player_unavailable_file_not_found),
            (rNeg as PlayerUiState.Unavailable).reason
        )
    }

    // V3 unchanged: KEPT session WITHOUT segmentIndex still refuses (merged-identity shape)
    @Test fun `kept-raw manifest without segmentIndex stays not-finished`() {
        val m = keptRawManifest(segments = listOf(seg("segment_0000.mp4", 30_000L)))
        val r = PlayerUriResolver.resolve(m, side = null, segmentIndex = null)
        assertTrue("expected Unavailable, got $r", r is PlayerUiState.Unavailable)
        assertEquals(
            UiText.Str(R.string.player_unavailable_not_finished),
            (r as PlayerUiState.Unavailable).reason
        )
    }
}
