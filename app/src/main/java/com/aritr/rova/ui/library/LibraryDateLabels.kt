package com.aritr.rova.ui.library

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Day-header label classification for the session-list (spec §3.5).
 *
 * Returns a KIND, not user copy: "Today"/"Yesterday" strings are resource-backed and
 * resolved in the composable layer (ADR-0022 / checkNoHardcodedUiStrings), while
 * weekday/date text is locale-formatted here (proper nouns, not UI copy).
 * [absolute] is always populated — it renders as the quiet secondary part of the header
 * ("Today · 2 Jul") and as the whole label for [DayHeaderKind.DATE].
 */
enum class DayHeaderKind { TODAY, YESTERDAY, WEEKDAY, DATE }

data class DayHeaderLabel(
    val kind: DayHeaderKind,
    /** Locale weekday name ("Tuesday"); non-null iff kind == WEEKDAY. */
    val weekday: String?,
    /** "2 Jul" same year, "31 Dec 2025" otherwise. */
    val absolute: String,
)

object LibraryDateLabels {

    fun headerLabel(dayMillis: Long, nowMillis: Long, locale: Locale, tz: TimeZone): DayHeaderLabel {
        val daysBack = dayDiff(from = dayMillis, to = nowMillis, tz = tz)
        val sameYear = yearOf(dayMillis, tz) == yearOf(nowMillis, tz)
        val absolute = format(if (sameYear) "d MMM" else "d MMM yyyy", dayMillis, locale, tz)
        return when {
            daysBack == 0L -> DayHeaderLabel(DayHeaderKind.TODAY, null, absolute)
            daysBack == 1L -> DayHeaderLabel(DayHeaderKind.YESTERDAY, null, absolute)
            daysBack in 2L..6L ->
                DayHeaderLabel(DayHeaderKind.WEEKDAY, format("EEEE", dayMillis, locale, tz), absolute)
            else -> DayHeaderLabel(DayHeaderKind.DATE, null, absolute) // incl. future days
        }
    }

    /** Whole calendar days between the two instants' local dates (positive = dayMillis is in the past). */
    private fun dayDiff(from: Long, to: Long, tz: TimeZone): Long {
        val a = startOfDay(from, tz)
        val b = startOfDay(to, tz)
        // Round, don't truncate: a DST transition makes a calendar day 23h/25h long.
        return Math.round((b - a).toDouble() / DAY_MS)
    }

    private fun startOfDay(millis: Long, tz: TimeZone): Long =
        Calendar.getInstance(tz).apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun yearOf(millis: Long, tz: TimeZone): Int =
        Calendar.getInstance(tz).apply { timeInMillis = millis }.get(Calendar.YEAR)

    private fun format(pattern: String, millis: Long, locale: Locale, tz: TimeZone): String =
        SimpleDateFormat(pattern, locale).apply { timeZone = tz }.format(Date(millis))

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
