package com.aritr.rova.service.dualrecord

import com.aritr.rova.service.dualrecord.internal.SegmentPathBuilder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SegmentPathBuilderTest {

    private val sessionDir = File("/sessions/abc-123")

    @Test
    fun `portrait sequence 1 yields segment_0001_P_mp4`() {
        val f = SegmentPathBuilder.build(sessionDir, sequence = 1, side = VideoSide.PORTRAIT)
        assertEquals(File(sessionDir, "segment_0001_P.mp4"), f)
    }

    @Test
    fun `landscape sequence 1 yields segment_0001_L_mp4`() {
        val f = SegmentPathBuilder.build(sessionDir, sequence = 1, side = VideoSide.LANDSCAPE)
        assertEquals(File(sessionDir, "segment_0001_L.mp4"), f)
    }

    @Test
    fun `four-digit zero-padding at sequence 10`() {
        assertEquals(
            File(sessionDir, "segment_0010_P.mp4"),
            SegmentPathBuilder.build(sessionDir, 10, VideoSide.PORTRAIT),
        )
    }

    @Test
    fun `four-digit zero-padding at sequence 100`() {
        assertEquals(
            File(sessionDir, "segment_0100_L.mp4"),
            SegmentPathBuilder.build(sessionDir, 100, VideoSide.LANDSCAPE),
        )
    }

    @Test
    fun `boundary sequence 9999 still four-digit`() {
        assertEquals(
            File(sessionDir, "segment_9999_P.mp4"),
            SegmentPathBuilder.build(sessionDir, 9999, VideoSide.PORTRAIT),
        )
    }

    @Test
    fun `sequence 0 or negative throws IllegalArgumentException`() {
        try {
            SegmentPathBuilder.build(sessionDir, sequence = 0, side = VideoSide.PORTRAIT)
            throw AssertionError("expected IllegalArgumentException for sequence == 0")
        } catch (_: IllegalArgumentException) { /* expected */ }

        try {
            SegmentPathBuilder.build(sessionDir, sequence = -1, side = VideoSide.LANDSCAPE)
            throw AssertionError("expected IllegalArgumentException for sequence == -1")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }
}
