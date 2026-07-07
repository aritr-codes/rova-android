package com.aritr.rova.ui.screens

import com.aritr.rova.data.AudioMode
import com.aritr.rova.data.ExportState
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SegmentRecord
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.screens.player.PlayerUiState
import com.aritr.rova.ui.screens.player.PlayerUriResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0037 §1/§5 — end-to-end pure-JVM proof that [HistoryArtifactMapper]'s
 * per-segment fanout (`resolveArtifactsPerSegment`) and
 * [PlayerUriResolver]'s segmentIndex dispatch stay index-aligned across the
 * FULL segments array, forever. Every [HistoryArtifactMapper.PerSegmentArtifact]
 * emitted by the mapper must resolve to `PlayerUiState.Ready` with the
 * matching `keptsegment://<filename>` URI — the mapper and the resolver
 * read the same `manifest.segments` array by the same `segmentIndex`, so a
 * future change that reorders, filters, or re-indexes one but not the other
 * fails this test.
 *
 * Covers both MULTI_SEGMENT_KEPT writer paths (`exportState = NOT_STARTED`
 * for the user "Keep as raw clips" CTA; `exportState = FAILED` for the
 * recovery classifier) and a DualShot interleaved P/L kept-raw manifest —
 * the resolver indexes the FULL interleaved array, side-blind (ADR-0037 §1).
 */
class KeptRawPlaybackJourneyTest {

    private fun manifest(
        sessionId: String,
        exportState: ExportState,
        segments: List<SegmentRecord>,
        topology: String = "Single",
    ): SessionManifest = SessionManifest(
        sessionId = sessionId,
        startedAt = 1_700_000_000_000L,
        config = SessionConfig(
            durationSeconds = 30,
            intervalSeconds = 60,
            resolution = "FHD",
            loopCount = segments.size.coerceAtLeast(1),
            captureTopology = topology,
        ),
        segments = segments,
        exportTier = ExportTier.TIER1_API29_PLUS,
        exportState = exportState,
        terminated = Terminated.MULTI_SEGMENT_KEPT,
        terminatedAt = 1L,
        stopRequested = false,
        stopReason = StopReason.NONE,
        audioMode = AudioMode.VIDEO_ONLY,
    )

    private fun seg(i: Int, side: VideoSide? = null) = SegmentRecord(
        filename = "segment_%04d.mp4".format(i + 1),
        durationMs = 1_000L,
        sizeBytes = 1L,
        sha1 = "sha-$i",
        side = side,
    )

    /** Fans out the manifest, then asserts every row resolves Ready at the exact sentinel URI. */
    private fun assertEveryRowResolvesReady(manifest: SessionManifest) {
        val rows = HistoryArtifactMapper.resolveArtifactsPerSegment(manifest)
        assertTrue("expected a fanned-out row per segment", rows.isNotEmpty())
        assertEquals(manifest.segments.size, rows.size)
        for (row in rows) {
            val state = PlayerUriResolver.resolve(manifest, side = null, segmentIndex = row.segmentIndex)
            assertTrue("row $row expected Ready, got $state", state is PlayerUiState.Ready)
            assertEquals(
                PlayerUriResolver.KEPT_SEGMENT_SCHEME + row.filename,
                (state as PlayerUiState.Ready).mediaUri,
            )
        }
    }

    @Test
    fun `user-keep NOT_STARTED manifest fans out to all Ready segments`() {
        val segments = (0..2).map { seg(it) }
        assertEveryRowResolvesReady(manifest("s-userkeep", ExportState.NOT_STARTED, segments))
    }

    @Test
    fun `classifier FAILED manifest fans out to all Ready segments`() {
        val segments = (0..2).map { seg(it) }
        assertEveryRowResolvesReady(manifest("s-classifier-failed", ExportState.FAILED, segments))
    }

    @Test
    fun `dualshot interleaved P L kept-raw manifest fans out to all Ready segments in full array order`() {
        val segments = listOf(
            seg(0, VideoSide.PORTRAIT),
            seg(1, VideoSide.LANDSCAPE),
            seg(2, VideoSide.PORTRAIT),
            seg(3, VideoSide.LANDSCAPE),
        )
        assertEveryRowResolvesReady(manifest("s-dualshot-kept", ExportState.NOT_STARTED, segments, topology = "DualShot"))
    }
}
