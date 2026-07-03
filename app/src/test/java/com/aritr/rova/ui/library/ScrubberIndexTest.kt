package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrubberIndexTest {

    // leading = 2 fixed items (recovery+warnings header, hero). Two groups: Today(3 rows), Yesterday(1 row).
    // item layout: 0 recovery, 1 hero, 2 Today-header, 3..5 Today-rows, 6 Yesterday-header, 7 Yesterday-row
    private val segs = ScrubberIndex.segments(
        groupLabels = listOf("Today", "Yesterday"),
        groupSizes = listOf(3, 1),
        leadingItemCount = 2,
    )

    @Test
    fun segments_accumulateHeaderPlusRows() {
        assertEquals(2, segs.size)
        assertEquals(ScrubberSegment("Today", 2, 4), segs[0])       // header + 3 rows
        assertEquals(ScrubberSegment("Yesterday", 6, 2), segs[1])   // header + 1 row
        assertEquals(5, segs[0].endItemIndex)
        assertEquals(7, segs[1].endItemIndex)
    }

    @Test
    fun labelForItemIndex_mapsToOwningGroup() {
        assertEquals("Today", ScrubberIndex.labelForItemIndex(segs, 0))   // leading → first group
        assertEquals("Today", ScrubberIndex.labelForItemIndex(segs, 4))
        assertEquals("Yesterday", ScrubberIndex.labelForItemIndex(segs, 6))
        assertEquals("Yesterday", ScrubberIndex.labelForItemIndex(segs, 99)) // past end → last group
    }

    @Test
    fun itemIndexForFraction_targetsGroupStart_andClamps() {
        assertEquals(2, ScrubberIndex.itemIndexForFraction(segs, 0f))   // first group header
        assertEquals(6, ScrubberIndex.itemIndexForFraction(segs, 1f))   // last group header
        assertEquals(2, ScrubberIndex.itemIndexForFraction(segs, -5f))  // clamp low
        assertEquals(6, ScrubberIndex.itemIndexForFraction(segs, 5f))   // clamp high
    }

    @Test
    fun labelForFraction_tracksDrag() {
        assertEquals("Today", ScrubberIndex.labelForFraction(segs, 0f))
        assertEquals("Yesterday", ScrubberIndex.labelForFraction(segs, 1f))
    }

    @Test
    fun segmentIndexForItemIndex_mapsToOwningSegment() {
        assertEquals(0, ScrubberIndex.segmentIndexForItemIndex(segs, 0))   // leading → first
        assertEquals(0, ScrubberIndex.segmentIndexForItemIndex(segs, 5))   // last Today row
        assertEquals(1, ScrubberIndex.segmentIndexForItemIndex(segs, 6))   // Yesterday header
        assertEquals(1, ScrubberIndex.segmentIndexForItemIndex(segs, 99))  // past end → last
    }

    @Test
    fun nearestSegmentIndex_roundsAndClamps() {
        assertEquals(0, ScrubberIndex.nearestSegmentIndex(segs, 0f))
        assertEquals(1, ScrubberIndex.nearestSegmentIndex(segs, 1f))
        assertEquals(0, ScrubberIndex.nearestSegmentIndex(segs, 0.2f))  // rounds to 0
        assertEquals(1, ScrubberIndex.nearestSegmentIndex(segs, 0.8f))  // rounds to 1
        assertEquals(0, ScrubberIndex.nearestSegmentIndex(segs, -3f))   // clamp low
        assertEquals(1, ScrubberIndex.nearestSegmentIndex(segs, 9f))    // clamp high
    }

    @Test
    fun emptySegments_areSafe() {
        val empty = emptyList<ScrubberSegment>()
        assertNull(ScrubberIndex.labelForItemIndex(empty, 0))
        assertNull(ScrubberIndex.labelForFraction(empty, 0.5f))
        assertEquals(0, ScrubberIndex.itemIndexForFraction(empty, 0.5f))
    }

    // --- PR-C: bubble geometry (label rides the thumb, clamped inside the rail) ---

    @Test
    fun bubbleTopPx_centersOnThumb_andClamps() {
        // Centered: thumb 16px tall at top=100 in a 400px rail, bubble 32px → 100+8-16 = 92.
        assertEquals(92f, ScrubberIndex.bubbleTopPx(100f, 16f, 32f, 400f))
        // Clamp top: thumb at 0 → centered would be negative → 0.
        assertEquals(0f, ScrubberIndex.bubbleTopPx(0f, 16f, 32f, 400f))
        // Clamp bottom: thumb at rail end (384) → centered 376 > 400-32=368 → 368.
        assertEquals(368f, ScrubberIndex.bubbleTopPx(384f, 16f, 32f, 400f))
        // Degenerate: bubble taller than rail → pinned to 0, never negative.
        assertEquals(0f, ScrubberIndex.bubbleTopPx(10f, 16f, 500f, 400f))
    }
}
