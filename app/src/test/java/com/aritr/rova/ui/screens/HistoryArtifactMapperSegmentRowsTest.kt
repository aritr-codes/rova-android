package com.aritr.rova.ui.screens

import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SegmentRecord
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.Terminated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 2 — pure-JVM cover for [HistoryArtifactMapper.resolveArtifactsPerSegment].
 * Verifies the flat 1-per-segment row enumeration for MULTI_SEGMENT_KEPT sessions;
 * other terminal states emit emptyList (caller falls back to the existing
 * single-artifact path or the per-side P+L path). Spec §5 #4 + §6.4.
 */
class HistoryArtifactMapperSegmentRowsTest {

    private fun manifest(
        sessionId: String,
        terminated: Terminated,
        segmentCount: Int,
        captureTopology: String = "Single",
    ): SessionManifest {
        val segs = (0 until segmentCount).map { i ->
            SegmentRecord(
                filename = "segment_$i.mp4",
                durationMs = 1_000L,
                sizeBytes = 1024L * (i + 1),
                sha1 = "sha$i",
            )
        }
        return SessionManifest(
            sessionId = sessionId,
            startedAt = 0L,
            config = SessionConfig(
                durationSeconds = 5,
                intervalMinutes = 1,
                resolution = "FHD",
                loopCount = 1,
                captureTopology = mode,
            ),
            segments = segs,
            exportTier = ExportTier.TIER1_API29_PLUS,
            terminated = terminated,
        )
    }

    @Test
    fun completed_session_with_segments_emits_empty_list() {
        val m = manifest("s1", Terminated.COMPLETED, segmentCount = 3)
        assertTrue(HistoryArtifactMapper.resolveArtifactsPerSegment(m).isEmpty())
    }

    @Test
    fun multi_segment_kept_with_five_segments_emits_five_rows() {
        val m = manifest("s2", Terminated.MULTI_SEGMENT_KEPT, segmentCount = 5)
        val artifacts = HistoryArtifactMapper.resolveArtifactsPerSegment(m)
        assertEquals(5, artifacts.size)
        assertEquals(listOf(0, 1, 2, 3, 4), artifacts.map { it.segmentIndex })
        assertEquals("segment_0.mp4", artifacts[0].filename)
        assertEquals("segment_4.mp4", artifacts[4].filename)
    }

    @Test
    fun multi_segment_kept_with_zero_segments_emits_empty_list() {
        val m = manifest("s3", Terminated.MULTI_SEGMENT_KEPT, segmentCount = 0)
        assertTrue(HistoryArtifactMapper.resolveArtifactsPerSegment(m).isEmpty())
    }

    @Test
    fun multi_segment_kept_with_one_segment_emits_one_row() {
        val m = manifest("s4", Terminated.MULTI_SEGMENT_KEPT, segmentCount = 1)
        val artifacts = HistoryArtifactMapper.resolveArtifactsPerSegment(m)
        assertEquals(1, artifacts.size)
        assertEquals(0, artifacts[0].segmentIndex)
    }
}
