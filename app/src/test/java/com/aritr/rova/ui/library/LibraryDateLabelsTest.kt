package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class LibraryDateLabelsTest {

    private val tz = TimeZone.getTimeZone("Asia/Kolkata")
    private val locale = Locale.US

    /** 2026-07-02 (Thu) 15:00 IST as "now". */
    private val now = at(2026, Calendar.JULY, 2, 15, 0)

    /** Instant at local wall-clock time in [tz]. */
    private fun at(y: Int, mo: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance(tz).apply {
            clear(); set(y, mo, d, h, min, 0)
        }.timeInMillis

    @Test
    fun sameDay_isToday() {
        val l = LibraryDateLabels.headerLabel(at(2026, Calendar.JULY, 2, 9, 12), now, locale, tz)
        assertEquals(DayHeaderKind.TODAY, l.kind)
        assertNull(l.weekday)
        assertEquals("2 Jul", l.absolute)
    }

    @Test
    fun previousDay_isYesterday() {
        val l = LibraryDateLabels.headerLabel(at(2026, Calendar.JULY, 1, 23, 59), now, locale, tz)
        assertEquals(DayHeaderKind.YESTERDAY, l.kind)
        assertEquals("1 Jul", l.absolute)
    }

    @Test
    fun twoToSixDaysBack_isWeekday() {
        // 2026-06-30 is a Tuesday.
        val l = LibraryDateLabels.headerLabel(at(2026, Calendar.JUNE, 30, 8, 0), now, locale, tz)
        assertEquals(DayHeaderKind.WEEKDAY, l.kind)
        assertEquals("Tuesday", l.weekday)
        assertEquals("30 Jun", l.absolute)
    }

    @Test
    fun sixDaysBack_isStillWeekday_sevenIsDate() {
        // 6 days back = 2026-06-26 (Friday) → WEEKDAY.
        val six = LibraryDateLabels.headerLabel(at(2026, Calendar.JUNE, 26, 8, 0), now, locale, tz)
        assertEquals(DayHeaderKind.WEEKDAY, six.kind)
        assertEquals("Friday", six.weekday)
        // 7 days back = 2026-06-25 → DATE (a bare weekday would be ambiguous with next week's).
        val seven = LibraryDateLabels.headerLabel(at(2026, Calendar.JUNE, 25, 8, 0), now, locale, tz)
        assertEquals(DayHeaderKind.DATE, seven.kind)
        assertEquals("25 Jun", seven.absolute)
    }

    @Test
    fun otherYear_dateIncludesYear() {
        val l = LibraryDateLabels.headerLabel(at(2025, Calendar.DECEMBER, 31, 8, 0), now, locale, tz)
        assertEquals(DayHeaderKind.DATE, l.kind)
        assertEquals("31 Dec 2025", l.absolute)
    }

    @Test
    fun futureDay_isDate_neverNegativeRelative() {
        val l = LibraryDateLabels.headerLabel(at(2026, Calendar.JULY, 3, 8, 0), now, locale, tz)
        assertEquals(DayHeaderKind.DATE, l.kind)
    }

    @Test
    fun midnightBoundary_lastMillisOfYesterdayVsFirstOfToday() {
        val startOfToday = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(DayHeaderKind.TODAY, LibraryDateLabels.headerLabel(startOfToday, now, locale, tz).kind)
        assertEquals(DayHeaderKind.YESTERDAY, LibraryDateLabels.headerLabel(startOfToday - 1, now, locale, tz).kind)
    }
}
