package com.aritr.rova.ui.library

import kotlin.math.roundToInt

/**
 * spec §5.4 — pure mapping between the date fast-scroll rail and the lazy list.
 * A [ScrubberSegment] is one day-group's slice of lazy-list item indices. The rail
 * runs fraction 0f (first/newest group) .. 1f (last/oldest group). Framework-free →
 * JVM-tested. The Screen owns the lazy-scroll side effects; this only computes indices.
 */
data class ScrubberSegment(val label: String, val startItemIndex: Int, val itemCount: Int) {
    val endItemIndex: Int get() = startItemIndex + itemCount - 1
}

object ScrubberIndex {

    /**
     * One segment per day group. [groupLabels] and [groupSizes] are parallel:
     * group i renders 1 header item + groupSizes[i] row items. [leadingItemCount]
     * is the fixed item count before the first group (recovery/warnings header +
     * optional hero) so indices line up with the LazyGrid/LazyColumn.
     */
    fun segments(groupLabels: List<String>, groupSizes: List<Int>, leadingItemCount: Int): List<ScrubberSegment> {
        val out = ArrayList<ScrubberSegment>(groupLabels.size)
        var idx = leadingItemCount
        for (i in groupLabels.indices) {
            val count = 1 + groupSizes[i].coerceAtLeast(0) // header + rows
            out.add(ScrubberSegment(groupLabels[i], idx, count))
            idx += count
        }
        return out
    }

    /** Segment index owning [firstVisibleItemIndex] (rest state; past end → last). */
    fun segmentIndexForItemIndex(segments: List<ScrubberSegment>, firstVisibleItemIndex: Int): Int {
        if (segments.isEmpty()) return 0
        val i = segments.indexOfFirst { firstVisibleItemIndex <= it.endItemIndex }
        return if (i < 0) segments.size - 1 else i
    }

    /** Nearest discrete segment index for a rail [fraction] (drag state); clamps 0..size-1. */
    fun nearestSegmentIndex(segments: List<ScrubberSegment>, fraction: Float): Int {
        if (segments.isEmpty()) return 0
        val f = fraction.coerceIn(0f, 1f)
        return (f * (segments.size - 1)).roundToInt().coerceIn(0, segments.size - 1)
    }

    /** Day label owning [firstVisibleItemIndex] (rest-state announce). */
    fun labelForItemIndex(segments: List<ScrubberSegment>, firstVisibleItemIndex: Int): String? {
        if (segments.isEmpty()) return null
        return segments[segmentIndexForItemIndex(segments, firstVisibleItemIndex)].label
    }

    /** Lazy-list item index to scroll to for a rail [fraction] (0f..1f → group start). */
    fun itemIndexForFraction(segments: List<ScrubberSegment>, fraction: Float): Int {
        if (segments.isEmpty()) return 0
        return segments[nearestSegmentIndex(segments, fraction)].startItemIndex
    }

    /** Day label for a rail [fraction] (drag-state bubble). */
    fun labelForFraction(segments: List<ScrubberSegment>, fraction: Float): String? {
        if (segments.isEmpty()) return null
        return segments[nearestSegmentIndex(segments, fraction)].label
    }
}
