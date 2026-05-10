package com.aritr.rova.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2.5 — pins the segmented-timeline math used at the bottom of
 * the in-app player. JVM-only, no Compose / ExoPlayer dependency.
 *
 * The test matrix locks the boundary semantics documented on
 * [SegmentedTimelineMath]:
 *  - exact end of segment i ⇒ segment i Done, segment i+1 Current(0f)
 *  - position ≥ total ⇒ all Done, currentClipIndex = totalClips
 *  - empty segment list ⇒ single Current(0f), index 1 of 1
 *  - zero-duration segment ⇒ instantly Done, no divide-by-zero
 */
class SegmentedTimelineMathTest {

    private fun done() = SegmentedTimelineMath.Cell.Done
    private fun upcoming() = SegmentedTimelineMath.Cell.Upcoming
    private fun current(f: Float) = SegmentedTimelineMath.Cell.Current(f)

    @Test
    fun `position 0 with three equal segments has only first as current`() {
        val r = SegmentedTimelineMath.compute(listOf(10_000, 10_000, 10_000), 0L)
        assertEquals(listOf(current(0f), upcoming(), upcoming()), r.cells)
        assertEquals(1, r.currentClipIndex)
        assertEquals(3, r.totalClips)
    }

    @Test
    fun `position mid-second-segment marks first done, second current half`() {
        val r = SegmentedTimelineMath.compute(listOf(10_000, 10_000, 10_000), 15_000L)
        assertEquals(listOf(done(), current(0.5f), upcoming()), r.cells)
        assertEquals(2, r.currentClipIndex)
    }

    @Test
    fun `position exactly at boundary i marks i Done and i+1 Current zero`() {
        val r = SegmentedTimelineMath.compute(listOf(10_000, 10_000), 10_000L)
        assertEquals(listOf(done(), current(0f)), r.cells)
        assertEquals(2, r.currentClipIndex)
    }

    @Test
    fun `position equal to total marks all Done`() {
        val r = SegmentedTimelineMath.compute(listOf(10_000, 10_000), 20_000L)
        assertEquals(listOf(done(), done()), r.cells)
        assertEquals(2, r.currentClipIndex)
        assertEquals(2, r.totalClips)
    }

    @Test
    fun `position past total clamps to all Done`() {
        val r = SegmentedTimelineMath.compute(listOf(10_000), 999_999L)
        assertEquals(listOf(done()), r.cells)
        assertEquals(1, r.currentClipIndex)
    }

    @Test
    fun `negative position clamps to zero and marks first Current`() {
        val r = SegmentedTimelineMath.compute(listOf(10_000, 10_000), -500L)
        assertEquals(listOf(current(0f), upcoming()), r.cells)
        assertEquals(1, r.currentClipIndex)
    }

    @Test
    fun `empty segments yields one synthetic Current cell`() {
        val r = SegmentedTimelineMath.compute(emptyList(), 0L)
        assertEquals(1, r.cells.size)
        assertTrue(r.cells.single() is SegmentedTimelineMath.Cell.Current)
        assertEquals(1, r.currentClipIndex)
        assertEquals(1, r.totalClips)
    }

    @Test
    fun `zero duration segment is instantly Done and does not divide by zero`() {
        val r = SegmentedTimelineMath.compute(listOf(10_000, 0, 10_000), 10_000L)
        assertEquals(listOf(done(), done(), current(0f)), r.cells)
        assertEquals(3, r.currentClipIndex)
    }

    @Test
    fun `single segment at half progress is Current with half fill`() {
        val r = SegmentedTimelineMath.compute(listOf(60_000), 30_000L)
        val cell = r.cells.single() as SegmentedTimelineMath.Cell.Current
        assertEquals(0.5f, cell.fillFraction, 0.001f)
        assertEquals(1, r.currentClipIndex)
        assertEquals(1, r.totalClips)
    }

    @Test
    fun `varied segment durations preserve per-segment fraction`() {
        // Segment 1: 5s, segment 2: 10s, segment 3: 5s. Position at 12s
        // → segment 1 done, segment 2 at 7s of 10s = 0.7, segment 3
        // upcoming.
        val r = SegmentedTimelineMath.compute(listOf(5_000, 10_000, 5_000), 12_000L)
        assertEquals(3, r.cells.size)
        assertEquals(done(), r.cells[0])
        val mid = r.cells[1] as SegmentedTimelineMath.Cell.Current
        assertEquals(0.7f, mid.fillFraction, 0.001f)
        assertEquals(upcoming(), r.cells[2])
        assertEquals(2, r.currentClipIndex)
    }
}
