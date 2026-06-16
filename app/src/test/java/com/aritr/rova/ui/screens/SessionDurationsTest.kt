package com.aritr.rova.ui.screens

import com.aritr.rova.data.SegmentRecord
import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionDurationsTest {

    private fun seg(name: String, dur: Long, side: VideoSide? = null) =
        SegmentRecord(filename = name, durationMs = dur, sizeBytes = 0L, sha1 = "x", side = side)

    // A 3-loop DualShot session: 3 portrait + 3 landscape segments (the bug case, scaled down).
    private val dualShot = listOf(
        seg("segment_0001_P.mp4", 5000, VideoSide.PORTRAIT),
        seg("segment_0001_L.mp4", 5000, VideoSide.LANDSCAPE),
        seg("segment_0002_P.mp4", 5000, VideoSide.PORTRAIT),
        seg("segment_0002_L.mp4", 5000, VideoSide.LANDSCAPE),
        seg("segment_0003_P.mp4", 5000, VideoSide.PORTRAIT),
        seg("segment_0003_L.mp4", 5000, VideoSide.LANDSCAPE),
    )

    @Test fun dualShotPortraitRow_countsOnlyPortraitSegments() {
        val d = SessionDurations.forRow(dualShot, isPerSegment = false, segmentFilename = null, side = VideoSide.PORTRAIT)
        assertEquals(3, d.size)            // 3 logical clips, not 6 files
        assertEquals(15_000L, d.sum())     // 30s session, not the doubled 30s×2
    }

    @Test fun dualShotLandscapeRow_countsOnlyLandscapeSegments() {
        val d = SessionDurations.forRow(dualShot, isPerSegment = false, segmentFilename = null, side = VideoSide.LANDSCAPE)
        assertEquals(3, d.size)
        assertEquals(15_000L, d.sum())
    }

    @Test fun singleMode_countsEverySegment() {
        val single = listOf(seg("a.mp4", 10_000), seg("b.mp4", 20_000))
        val d = SessionDurations.forRow(single, isPerSegment = false, segmentFilename = null, side = null)
        assertEquals(listOf(10_000L, 20_000L), d)
    }

    @Test fun perSegmentRow_isJustTheMatchedSegment() {
        val single = listOf(seg("a.mp4", 10_000), seg("b.mp4", 20_000), seg("c.mp4", 30_000))
        val d = SessionDurations.forRow(single, isPerSegment = true, segmentFilename = "b.mp4", side = null)
        assertEquals(listOf(20_000L), d)
    }

    @Test fun perSegmentRow_unmatchedFilename_isZero() {
        val single = listOf(seg("a.mp4", 10_000))
        val d = SessionDurations.forRow(single, isPerSegment = true, segmentFilename = null, side = null)
        assertEquals(listOf(0L), d)
    }
}
