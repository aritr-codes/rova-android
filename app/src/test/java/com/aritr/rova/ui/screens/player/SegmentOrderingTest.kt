package com.aritr.rova.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentOrderingTest {
    @Test fun `parses dual sequence`() {
        assertEquals(7, SegmentOrdering.parseSequence("segment_0007_P.mp4"))
        assertEquals(7, SegmentOrdering.parseSequence("segment_0007_L.mp4"))
    }
    @Test fun `parses single sequence`() {
        assertEquals(3, SegmentOrdering.parseSequence("segment_0003.mp4"))
    }
    @Test fun `unparseable returns null`() {
        assertNull(SegmentOrdering.parseSequence("weird.mp4"))
    }
    @Test fun `orders out-of-order filenames by sequence`() {
        val names = listOf("segment_0002_P.mp4", "segment_0001_P.mp4", "segment_0003_P.mp4")
        assertEquals(listOf(1, 0, 2), SegmentOrdering.orderedIndices(names))
    }
    @Test fun `stable for unparseable — preserves original order`() {
        val names = listOf("a.mp4", "b.mp4")
        assertEquals(listOf(0, 1), SegmentOrdering.orderedIndices(names))
    }
}
