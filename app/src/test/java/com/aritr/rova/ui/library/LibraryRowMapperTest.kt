package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.data.ExportState
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class LibraryRowMapperTest {

    private val locale = Locale.US
    private val tz = TimeZone.getTimeZone("UTC")

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance(tz, locale); c.clear(); c.set(y, mo, d, h, mi, 0)
        c.set(Calendar.MILLISECOND, 0); return c.timeInMillis
    }

    private fun input(
        customTitle: String? = null,
        favorite: Boolean = false,
        topology: String = "Single",
        terminated: Terminated? = Terminated.COMPLETED,
        stopReason: StopReason = StopReason.NONE,
        export: ExportState = ExportState.FINALIZED,
        segmentDurationsMs: List<Long> = listOf(120_000L),
        startedAt: Long = millis(2026, Calendar.JUNE, 14, 14, 32),
        side: VideoSide? = null,
        sessionId: String? = null,
    ) = LibraryRowMapper.Input(
        stableKey = "/a.mp4",
        startedAtMillis = startedAt,
        dateMillis = startedAt,
        dateLabel = "Jun 14 · 2:32 PM",
        sizeBytes = 50_000_000L,
        segmentDurationsMs = segmentDurationsMs,
        topologyPersisted = topology,
        terminated = terminated,
        stopReason = stopReason,
        exportState = export,
        customTitle = customTitle,
        favorite = favorite,
        side = side,
        sessionId = sessionId,
    )

    @Test fun `derives title from SmartTitle when no custom title`() {
        val row = LibraryRowMapper.map(input(segmentDurationsMs = listOf(60_000L, 60_000L)), locale, tz)
        // Title is the concise day·time "name"; clips/duration moved to the meta line (owner polish).
        assertEquals("Sun · 2:32 PM", row.title)
    }

    @Test fun `custom title overrides derived`() {
        assertEquals("Beach", LibraryRowMapper.map(input(customTitle = "Beach"), locale, tz).title)
    }

    @Test fun `sums segment durations`() {
        val row = LibraryRowMapper.map(input(segmentDurationsMs = listOf(10_000L, 20_000L, 30_000L)), locale, tz)
        assertEquals(60_000L, row.durationMs)
    }

    @Test fun `parses topology and surfaces P+L`() {
        assertEquals(CaptureTopology.DualShot, LibraryRowMapper.map(input(topology = "DualShot"), locale, tz).topology)
        assertEquals(CaptureTopology.Single, LibraryRowMapper.map(input(topology = "bogus"), locale, tz).topology)
    }

    @Test fun `exceptional badge only`() {
        assertEquals(null, LibraryRowMapper.map(input(), locale, tz).badge)
        assertEquals(
            LibraryBadge.RECOVERED,
            LibraryRowMapper.map(input(terminated = Terminated.MULTI_SEGMENT_KEPT), locale, tz).badge,
        )
        assertEquals(
            LibraryBadge.INTERRUPTED,
            LibraryRowMapper.map(input(export = ExportState.FAILED), locale, tz).badge,
        )
    }

    @Test fun `carries favorite stableKey size and dateLabel`() {
        val row = LibraryRowMapper.map(input(favorite = true), locale, tz)
        assertEquals("/a.mp4", row.stableKey)
        assertEquals(true, row.favorite)
        assertEquals(50_000_000L, row.sizeBytes)
        assertEquals("Jun 14 · 2:32 PM", row.dateLabel)
    }

    @Test fun `thermal auto-stop row is AutoStopped`() {
        assertEquals(
            LibraryBadge.AUTO_STOPPED,
            LibraryRowMapper.map(input(terminated = Terminated.USER_STOPPED, stopReason = StopReason.THERMAL), locale, tz).badge,
        )
    }

    @Test fun `map_carriesSessionKeyAndSide`() {
        val row = LibraryRowMapper.map(input(side = VideoSide.PORTRAIT, sessionId = "abc123"), locale, tz)
        assertEquals("session:abc123", row.sessionKey)
        assertEquals(VideoSide.PORTRAIT, row.side)
        assertEquals(emptyList<LibrarySessionSide>(), row.sides)
    }

    @Test fun `map_nullSessionId_yieldsNullSessionKey`() {
        val row = LibraryRowMapper.map(input(), locale, tz)
        assertNull(row.sessionKey)
        assertNull(row.side)
    }

    @Test
    fun map_carriesResumePosition() {
        // Build the same Input as map_carriesSessionKeyAndSide but with resumePositionMs = 42_000L.
        // Assert: row.resumePositionMs == 42_000L. Also assert the default (an Input built without
        // the param) yields null.
        val row = LibraryRowMapper.map(
            LibraryRowMapper.Input(
                stableKey = "/path/a_P.mp4",
                startedAtMillis = 1_000L,
                dateMillis = 1_000L,
                dateLabel = "Jul 2",
                sizeBytes = 10L,
                segmentDurationsMs = listOf(30_000L),
                topologyPersisted = "DualShot",
                terminated = null,
                stopReason = StopReason.NONE,
                exportState = ExportState.FINALIZED,
                customTitle = null,
                favorite = false,
                side = VideoSide.PORTRAIT,
                sessionId = "abc123",
                resumePositionMs = 42_000L,
            ),
            Locale.US, TimeZone.getTimeZone("UTC"),
        )
        assertEquals(42_000L, row.resumePositionMs)
    }

    @Test
    fun `per_segment_kept_raw_reads_own_slot_no_bleed`() {
        // Kept-raw segment rows must read from their own "#seg<N>" slot, not the legacy ""
        // slot. Metadata has both "" (5_000L) and "#seg1" (9_000L). Segment 0 row should read
        // from "#seg0" (not in metadata) → null (NOT 5_000L). Segment 1 row should read from
        // "#seg1" → 9_000L. ADR-0037 §4 truthfulness.
        val row0 = LibraryRowMapper.map(
            LibraryRowMapper.Input(
                stableKey = "/path/seg0.mp4",
                startedAtMillis = 1_000L,
                dateMillis = 1_000L,
                dateLabel = "Jul 2",
                sizeBytes = 10L,
                segmentDurationsMs = listOf(30_000L),
                topologyPersisted = "Single",
                terminated = null,
                stopReason = StopReason.NONE,
                exportState = ExportState.FINALIZED,
                customTitle = null,
                favorite = false,
                side = null,
                sessionId = null,
                segmentIndex = 0,
                resumePositionMs = null, // Will be computed from slotFor(null, 0) = "#seg0"
            ),
            Locale.US, TimeZone.getTimeZone("UTC"),
        )
        val row1 = LibraryRowMapper.map(
            LibraryRowMapper.Input(
                stableKey = "/path/seg1.mp4",
                startedAtMillis = 1_000L,
                dateMillis = 1_000L,
                dateLabel = "Jul 2",
                sizeBytes = 10L,
                segmentDurationsMs = listOf(30_000L),
                topologyPersisted = "Single",
                terminated = null,
                stopReason = StopReason.NONE,
                exportState = ExportState.FINALIZED,
                customTitle = null,
                favorite = false,
                side = null,
                sessionId = null,
                segmentIndex = 1,
                resumePositionMs = 9_000L, // Will be read from slotFor(null, 1) = "#seg1"
            ),
            Locale.US, TimeZone.getTimeZone("UTC"),
        )
        assertNull(row0.resumePositionMs)
        assertEquals(9_000L, row1.resumePositionMs)
    }
}
