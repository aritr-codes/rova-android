package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.data.StopReason
import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySessionAggregatorTest {

    private fun row(
        key: String,
        topology: CaptureTopology = CaptureTopology.Single,
        sessionKey: String? = null,
        side: VideoSide? = null,
        dateMillis: Long = 100L,
        durationMs: Long = 60_000L,
        sizeBytes: Long = 10L,
        clipCount: Int = 2,
        favorite: Boolean = false,
        resumePositionMs: Long? = null,
    ) = LibraryRow(
        stableKey = key, title = "t", dateLabel = "d", dateMillis = dateMillis,
        durationMs = durationMs, sizeBytes = sizeBytes, clipCount = clipCount,
        topology = topology, badge = null, favorite = favorite,
        sessionKey = sessionKey, side = side,
        resumePositionMs = resumePositionMs,
    )

    @Test
    fun singleRows_passThroughUntouched() {
        val rows = listOf(row("a"), row("b"))
        assertEquals(rows, LibrarySessionAggregator.aggregate(rows))
    }

    @Test
    fun dualShotPair_collapsesToOneSessionRow() {
        val p = row("/p.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.PORTRAIT,
            dateMillis = 200L, durationMs = 90_000L, sizeBytes = 30L, clipCount = 3)
        val l = row("/l.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.LANDSCAPE,
            dateMillis = 201L, durationMs = 91_000L, sizeBytes = 32L, clipCount = 3, favorite = true)
        val out = LibrarySessionAggregator.aggregate(listOf(p, l))

        assertEquals(1, out.size)
        val s = out.single()
        assertEquals("session:s1", s.stableKey)
        assertEquals(62L, s.sizeBytes)          // sum
        assertEquals(91_000L, s.durationMs)     // max, never sum (concurrent sides)
        assertEquals(3, s.clipCount)            // max, no N×2
        assertEquals(201L, s.dateMillis)        // max
        assertTrue(s.favorite)                  // any
        assertNull(s.side)
        assertNull(s.orientation)
        assertEquals(listOf(VideoSide.PORTRAIT, VideoSide.LANDSCAPE), s.sides.map { it.side })
        assertEquals("/p.mp4", s.sides[0].stableKey)   // original per-side keys preserved
        assertEquals(90_000L, s.sides[0].durationMs)
    }

    @Test
    fun collapsedRow_sitsAtFirstOccurrencePosition_othersUndisturbed() {
        val a = row("a", dateMillis = 300L)
        val p = row("/p.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.PORTRAIT, dateMillis = 250L)
        val b = row("b", dateMillis = 220L)
        val l = row("/l.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.LANDSCAPE, dateMillis = 249L)
        val out = LibrarySessionAggregator.aggregate(listOf(a, p, b, l))
        assertEquals(listOf("a", "session:s1", "b"), out.map { it.stableKey })
    }

    @Test
    fun singleSideOnly_passesThroughAsIs() {
        val p = row("/p.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.PORTRAIT)
        assertEquals(listOf(p), LibrarySessionAggregator.aggregate(listOf(p)))
    }

    @Test
    fun sessionlessDualShot_passesThroughAsIs() {
        val legacy = row("/old.mp4", CaptureTopology.DualShot, sessionKey = null, side = VideoSide.PORTRAIT)
        assertEquals(listOf(legacy), LibrarySessionAggregator.aggregate(listOf(legacy)))
    }

    @Test
    fun sameSideDuplicate_keptOnceNotCollapsed() {
        val l1 = row("/l1.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.LANDSCAPE, durationMs = 10L)
        val l2 = row("/l2.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.LANDSCAPE, durationMs = 20L)
        val out = LibrarySessionAggregator.aggregate(listOf(l1, l2))
        // Same-side duplicates: first kept, duplicate dropped → one pass-through row (not a collapse).
        assertEquals(listOf(l1), out)
    }

    @Test
    fun keptSingle_sameKeyTwiceInInput_emitsOnce() {
        // Helper is self-sufficient (codex): don't rely on upstream stableKey de-dup.
        val p = row("/p.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.PORTRAIT)
        val out = LibrarySessionAggregator.aggregate(listOf(p, p.copy()))
        assertEquals(listOf(p), out)
    }

    @Test
    fun collapse_baseIsLatestDatedRow_noLabelDrift() {
        // dateMillis/dateLabel/title must come from ONE row (codex: sort-vs-display drift).
        val p = row("/p.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.PORTRAIT, dateMillis = 100L)
            .copy(dateLabel = "old", title = "old-title")
        val l = row("/l.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.LANDSCAPE, dateMillis = 200L)
            .copy(dateLabel = "new", title = "new-title")
        val s = LibrarySessionAggregator.aggregate(listOf(p, l)).single()
        assertEquals(200L, s.dateMillis)
        assertEquals("new", s.dateLabel)
        assertEquals("new-title", s.title)
        // Sides order stays PORTRAIT-first regardless of base choice.
        assertEquals(listOf(VideoSide.PORTRAIT, VideoSide.LANDSCAPE), s.sides.map { it.side })
    }

    @Test
    fun twoDifferentSessions_dontMerge() {
        val p1 = row("/p1.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.PORTRAIT)
        val l1 = row("/l1.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.LANDSCAPE)
        val p2 = row("/p2.mp4", CaptureTopology.DualShot, "session:s2", VideoSide.PORTRAIT)
        val l2 = row("/l2.mp4", CaptureTopology.DualShot, "session:s2", VideoSide.LANDSCAPE)
        val out = LibrarySessionAggregator.aggregate(listOf(p1, l1, p2, l2))
        assertEquals(listOf("session:s1", "session:s2"), out.map { it.stableKey })
    }

    @Test
    fun collapse_resumePosition_portraitWins_elseLandscape() {
        val p = row("/p.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.PORTRAIT, dateMillis = 100L)
            .copy(resumePositionMs = 11_000L)
        val l = row("/l.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.LANDSCAPE, dateMillis = 200L)
            .copy(resumePositionMs = 22_000L)
        // Portrait-first even though landscape is the latest-dated base.
        assertEquals(11_000L, LibrarySessionAggregator.aggregate(listOf(p, l)).single().resumePositionMs)

        val pNull = p.copy(resumePositionMs = null)
        assertEquals(22_000L, LibrarySessionAggregator.aggregate(listOf(pNull, l)).single().resumePositionMs)

        val bothNull = listOf(pNull, l.copy(resumePositionMs = null))
        assertNull(LibrarySessionAggregator.aggregate(bothNull).single().resumePositionMs)
    }

    @Test
    fun collapse_sidesCarryTheirOwnResumePositions() {
        // v3.3 per-pane hairline: each LibrarySessionSide keeps ITS side's exact position — the
        // scalar portrait-first fold must not erase the landscape pane's own value.
        val p = row("/p.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.PORTRAIT)
            .copy(resumePositionMs = 11_000L)
        val l = row("/l.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.LANDSCAPE)
            .copy(resumePositionMs = 22_000L)
        val sides = LibrarySessionAggregator.aggregate(listOf(p, l)).single().sides
        assertEquals(11_000L, sides.first { it.side == VideoSide.PORTRAIT }.resumePositionMs)
        assertEquals(22_000L, sides.first { it.side == VideoSide.LANDSCAPE }.resumePositionMs)

        val pNull = p.copy(resumePositionMs = null)
        val sides2 = LibrarySessionAggregator.aggregate(listOf(pNull, l)).single().sides
        assertNull(sides2.first { it.side == VideoSide.PORTRAIT }.resumePositionMs)
        assertEquals(22_000L, sides2.first { it.side == VideoSide.LANDSCAPE }.resumePositionMs)
    }
}
