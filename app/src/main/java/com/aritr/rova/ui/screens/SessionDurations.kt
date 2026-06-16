package com.aritr.rova.ui.screens

import com.aritr.rova.data.SegmentRecord
import com.aritr.rova.service.dualrecord.VideoSide

/**
 * Pure selector for the segment durations a Library row should attribute to itself
 * (bug fix 2026-06-15: DualShot clip-count/duration over-count). Android-free -> JVM-tested.
 *
 *  - per-segment row ([isPerSegment]): exactly the one segment matched by [segmentFilename]
 *    (a MULTI_SEGMENT_KEPT fanout row stands for a single clip).
 *  - DualShot per-side row ([side] != null): ONLY that side's segments. A DualShot capture writes a
 *    portrait AND a landscape segment per loop, so a 6-clip session holds 12 segments (6 portrait +
 *    6 landscape). Counting all of them reported N×2 clips and double-counted the (concurrent) duration.
 *  - otherwise (single-mode session): every segment.
 */
object SessionDurations {
    fun forRow(
        segments: List<SegmentRecord>,
        isPerSegment: Boolean,
        segmentFilename: String?,
        side: VideoSide?,
    ): List<Long> = when {
        isPerSegment -> listOf(segments.firstOrNull { it.filename == segmentFilename }?.durationMs ?: 0L)
        side != null -> segments.filter { it.side == side }.map { it.durationMs }
        else -> segments.map { it.durationMs }
    }
}
