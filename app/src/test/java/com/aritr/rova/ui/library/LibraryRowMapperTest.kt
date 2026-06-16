package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.data.ExportState
import com.aritr.rova.data.Terminated
import org.junit.Assert.assertEquals
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
        export: ExportState = ExportState.FINALIZED,
        segmentDurationsMs: List<Long> = listOf(120_000L),
        startedAt: Long = millis(2026, Calendar.JUNE, 14, 14, 32),
    ) = LibraryRowMapper.Input(
        stableKey = "/a.mp4",
        startedAtMillis = startedAt,
        dateMillis = startedAt,
        dateLabel = "Jun 14 · 2:32 PM",
        sizeBytes = 50_000_000L,
        segmentDurationsMs = segmentDurationsMs,
        topologyPersisted = topology,
        terminated = terminated,
        exportState = export,
        customTitle = customTitle,
        favorite = favorite,
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
}
