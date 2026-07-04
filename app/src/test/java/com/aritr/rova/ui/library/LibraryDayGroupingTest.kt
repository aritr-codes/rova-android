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
        LibraryRow(key, key, "", date, 0, size, 1, CaptureTopology.Single, null, false)

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

    // --- Bug-fix 2026-06-16: sort crash on Largest/Longest (duplicate LazyList header keys) ---

    /** Rows ordered by SIZE span two days interleaved — the exact shape that crashed the grid. */
    private fun interleavedBySize(now: Long) = listOf(
        row("big-today", millis(2026, Calendar.JUNE, 14), 4096),
        row("big-yest", millis(2026, Calendar.JUNE, 13), 3072),
        row("small-today", millis(2026, Calendar.JUNE, 14), 1024),
    )

    @Test fun `groupForSort LARGEST returns one header-less bucket (no duplicate labels)`() {
        val now = millis(2026, Calendar.JUNE, 14)
        val groups = LibraryDayGrouping.groupForSort(interleavedBySize(now), LibrarySort.LARGEST, now, locale, tz)
        assertEquals(1, groups.size)
        assertEquals("", groups[0].label) // empty label => caller suppresses the day header
        assertEquals(listOf("big-today", "big-yest", "small-today"), groups[0].rows.map { it.stableKey })
        assertEquals("8.0 KB", groups[0].sizeTotalLabel)
    }

    @Test fun `groupForSort LONGEST also flattens`() {
        val now = millis(2026, Calendar.JUNE, 14)
        val groups = LibraryDayGrouping.groupForSort(interleavedBySize(now), LibrarySort.LONGEST, now, locale, tz)
        assertEquals(1, groups.size)
        assertEquals("", groups[0].label)
    }

    @Test fun `groupForSort NEWEST delegates to group (day buckets retained)`() {
        val now = millis(2026, Calendar.JUNE, 14)
        val rows = listOf(
            row("a", millis(2026, Calendar.JUNE, 14), 1024),
            row("b", millis(2026, Calendar.JUNE, 14), 1024),
            row("c", millis(2026, Calendar.JUNE, 13), 2048),
        )
        assertEquals(
            LibraryDayGrouping.group(rows, now, locale, tz),
            LibraryDayGrouping.groupForSort(rows, LibrarySort.NEWEST, now, locale, tz),
        )
    }

    /** The invariant that the crash violated: group labels are unique for EVERY sort. */
    @Test fun `no sort produces duplicate group labels`() {
        val now = millis(2026, Calendar.JUNE, 14)
        for (sort in LibrarySort.entries) {
            val sorted = LibraryQuery.collection(
                interleavedBySize(now), sort, LibraryFilter(), heroKey = null,
            )
            val labels = LibraryDayGrouping.groupForSort(sorted, sort, now, locale, tz).map { it.label }
            assertEquals("duplicate labels under $sort", labels.size, labels.toSet().size)
        }
    }

    @Test fun `groupForSort empty input yields no groups for non-chronological sort`() {
        assertEquals(
            emptyList<LibraryDayGroup>(),
            LibraryDayGrouping.groupForSort(emptyList(), LibrarySort.LARGEST, millis(2026, Calendar.JUNE, 14), locale, tz),
        )
    }

    // --- PR-C: dayEpochMillis (sticky-header keys stable across midnight) ---

    @Test fun `group stamps each bucket with its local day epoch`() {
        val now = millis(2026, Calendar.JUNE, 14)
        val rows = listOf(
            row("a", millis(2026, Calendar.JUNE, 14), 1024),
            row("b", millis(2026, Calendar.JUNE, 13), 2048),
        )
        val groups = LibraryDayGrouping.group(rows, now, locale, tz)
        assertEquals(2, groups.size)
        assertEquals(LibraryDateLabels.dayEpoch(millis(2026, Calendar.JUNE, 14), tz), groups[0].dayEpochMillis)
        assertEquals(LibraryDateLabels.dayEpoch(millis(2026, Calendar.JUNE, 13), tz), groups[1].dayEpochMillis)
        // Distinct per day — the LazyList duplicate-key invariant for the new header keys.
        assertEquals(groups.size, groups.map { it.dayEpochMillis }.distinct().size)
    }

    @Test fun `flat bucket carries the zero epoch (header suppressed, key unused)`() {
        val now = millis(2026, Calendar.JUNE, 14)
        val groups = LibraryDayGrouping.groupForSort(interleavedBySize(now), LibrarySort.LARGEST, now, locale, tz)
        assertEquals(0L, groups[0].dayEpochMillis)
    }
}
