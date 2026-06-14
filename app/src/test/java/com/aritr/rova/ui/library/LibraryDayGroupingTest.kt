package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class LibraryDayGroupingTest {

    private val locale = Locale.US
    private val tz = TimeZone.getTimeZone("UTC")

    private fun millis(y: Int, mo: Int, d: Int): Long {
        val c = Calendar.getInstance(tz, locale); c.clear(); c.set(y, mo, d, 12, 0, 0)
        c.set(Calendar.MILLISECOND, 0); return c.timeInMillis
    }

    private fun row(key: String, date: Long, size: Long) =
        LibraryRow(key, key, "", date, 0, size, CaptureTopology.Single, null, false)

    @Test fun `buckets by day in input order with per-day size totals`() {
        val now = millis(2026, Calendar.JUNE, 14)
        val rows = listOf(
            row("a", millis(2026, Calendar.JUNE, 14), 1024),
            row("b", millis(2026, Calendar.JUNE, 14), 1024),
            row("c", millis(2026, Calendar.JUNE, 13), 2048),
        )
        val groups = LibraryDayGrouping.group(rows, now, locale, tz)
        assertEquals(2, groups.size)
        assertEquals("Today", groups[0].label)
        assertEquals(listOf("a", "b"), groups[0].rows.map { it.stableKey })
        assertEquals("2.0 KB", groups[0].sizeTotalLabel)
        assertEquals("Yesterday", groups[1].label)
        assertEquals("2.0 KB", groups[1].sizeTotalLabel)
    }

    @Test fun `empty input yields no groups`() {
        assertEquals(emptyList<LibraryDayGroup>(), LibraryDayGrouping.group(emptyList(), millis(2026, Calendar.JUNE, 14), locale, tz))
    }
}
