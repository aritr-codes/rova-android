package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    // --- PR-C: dayEpoch (stable day identity for sticky-header keys) ---

    @Test fun `dayEpoch same local day maps to one epoch`() {
        val tz = TimeZone.getTimeZone("UTC")
        val morning = 1_751_500_800_000L + 8 * 3600_000L   // 2025-07-03 08:00 UTC
        val evening = 1_751_500_800_000L + 22 * 3600_000L  // 2025-07-03 22:00 UTC
        assertEquals(LibraryDateLabels.dayEpoch(morning, tz), LibraryDateLabels.dayEpoch(evening, tz))
    }

    @Test fun `dayEpoch flips exactly at local midnight`() {
        val tz = TimeZone.getTimeZone("UTC")
        val beforeMidnight = 1_751_500_800_000L + 24 * 3600_000L - 1_000L // 23:59:59
        val afterMidnight = 1_751_500_800_000L + 24 * 3600_000L + 1_000L  // 00:00:01 next day
        assertNotEquals(LibraryDateLabels.dayEpoch(beforeMidnight, tz), LibraryDateLabels.dayEpoch(afterMidnight, tz))
    }

    @Test fun `dayEpoch is the local day floor across a DST transition`() {
        // America/New_York springs forward 2026-03-08 (23h day). Noon that day must floor to that
        // day's local midnight, distinct from both neighbours.
        val tz = TimeZone.getTimeZone("America/New_York")
        val cal = java.util.Calendar.getInstance(tz).apply {
            clear(); set(2026, java.util.Calendar.MARCH, 8, 12, 0, 0)
        }
        val noonDst = cal.timeInMillis
        val epoch = LibraryDateLabels.dayEpoch(noonDst, tz)
        val midnight = java.util.Calendar.getInstance(tz).apply {
            clear(); set(2026, java.util.Calendar.MARCH, 8, 0, 0, 0)
        }.timeInMillis
        assertEquals(midnight, epoch)
        assertNotEquals(epoch, LibraryDateLabels.dayEpoch(noonDst - 24 * 3600_000L, tz))
        assertNotEquals(epoch, LibraryDateLabels.dayEpoch(noonDst + 24 * 3600_000L, tz))
    }

    @Test fun `dayEpoch is the local day floor across a fall-back DST transition`() {
        // America/New_York falls back 2026-11-01 (25h day) — both DST directions pinned
        // (codex plan-review 2026-07-03). 23:30 that local day must still floor to the SAME
        // local midnight as 01:30, despite 25 wall-clock-spanning hours.
        val tz = TimeZone.getTimeZone("America/New_York")
        fun at(h: Int, min: Int): Long = java.util.Calendar.getInstance(tz).apply {
            clear(); set(2026, java.util.Calendar.NOVEMBER, 1, h, min, 0)
        }.timeInMillis
        val midnight = at(0, 0)
        assertEquals(midnight, LibraryDateLabels.dayEpoch(at(1, 30), tz))
        assertEquals(midnight, LibraryDateLabels.dayEpoch(at(23, 30), tz))
        assertNotEquals(midnight, LibraryDateLabels.dayEpoch(at(23, 30) + 3600_000L, tz)) // 00:30 next day
    }

    // --- dayAge (rounded calendar-day diff, DST-safe) ---

    @Test fun `dayAge counts calendar days DST-safely`() {
        // Europe/Berlin springs forward 2024-03-31 (23h day). Noon on the DST-spring day vs
        // noon the day before must still report a 1-day age via the rounded diff, not raw
        // millis truncation, and same-day must report 0.
        val tz = TimeZone.getTimeZone("Europe/Berlin")
        fun noon(d: Int): Long = Calendar.getInstance(tz).apply {
            clear(); set(2024, Calendar.MARCH, d, 12, 0, 0)
        }.timeInMillis
        val nowMillis = noon(31)
        val today = LibraryDateLabels.dayEpoch(nowMillis, tz)
        val yesterday = LibraryDateLabels.dayEpoch(noon(30), tz)
        assertEquals(0, LibraryDateLabels.dayAge(today, nowMillis, tz))
        assertEquals(1, LibraryDateLabels.dayAge(yesterday, nowMillis, tz))
    }

    @Test fun `dayAge clamps future days at zero`() {
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val tomorrow = LibraryDateLabels.dayEpoch(at(2026, Calendar.JULY, 3, 8, 0), tz)
        assertEquals(0, LibraryDateLabels.dayAge(tomorrow, now, tz))
    }
}
