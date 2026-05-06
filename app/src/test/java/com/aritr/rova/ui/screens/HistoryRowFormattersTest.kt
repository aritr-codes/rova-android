package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Slice 4 — pure JVM tests for the Library row formatters. Locale
 * and timezone are pinned to US/UTC so assertions hold regardless of
 * the test host's defaults.
 */
class HistoryRowFormattersTest {

    private val locale = Locale.US
    private val tz = TimeZone.getTimeZone("UTC")

    private fun millisOf(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        val cal = Calendar.getInstance(tz, locale)
        cal.clear()
        cal.set(year, month, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ─── formatPrimaryDateTime ─────────────────────────────────────

    @Test
    fun `formatPrimaryDateTime renders mockup example`() {
        val pm = millisOf(2026, Calendar.MAY, 4, 14, 22)
        assertEquals(
            "May 4 · 2:22 PM",
            HistoryRowFormatters.formatPrimaryDateTime(pm, locale, tz)
        )
    }

    @Test
    fun `formatPrimaryDateTime renders AM hours without leading zero`() {
        val am = millisOf(2026, Calendar.MAY, 4, 8, 11)
        assertEquals(
            "May 4 · 8:11 AM",
            HistoryRowFormatters.formatPrimaryDateTime(am, locale, tz)
        )
    }

    @Test
    fun `formatPrimaryDateTime renders 12 PM as noon`() {
        val noon = millisOf(2026, Calendar.MAY, 4, 12, 0)
        assertEquals(
            "May 4 · 12:00 PM",
            HistoryRowFormatters.formatPrimaryDateTime(noon, locale, tz)
        )
    }

    // ─── formatTime24 ──────────────────────────────────────────────

    @Test
    fun `formatTime24 zero-pads hour and minute`() {
        val midnight = millisOf(2026, Calendar.MAY, 4, 0, 7)
        assertEquals(
            "00:07",
            HistoryRowFormatters.formatTime24(midnight, locale, tz)
        )
        val late = millisOf(2026, Calendar.MAY, 4, 22, 3)
        assertEquals(
            "22:03",
            HistoryRowFormatters.formatTime24(late, locale, tz)
        )
    }

    // ─── formatGroupHeader ─────────────────────────────────────────

    @Test
    fun `formatGroupHeader returns Today when same calendar day`() {
        val now = millisOf(2026, Calendar.MAY, 4, 14, 22)
        val same = millisOf(2026, Calendar.MAY, 4, 8, 11)
        assertEquals(
            "Today",
            HistoryRowFormatters.formatGroupHeader(same, now, locale, tz)
        )
    }

    @Test
    fun `formatGroupHeader returns Yesterday when one calendar day prior`() {
        val now = millisOf(2026, Calendar.MAY, 4, 8, 11)
        val yesterday = millisOf(2026, Calendar.MAY, 3, 23, 59)
        assertEquals(
            "Yesterday",
            HistoryRowFormatters.formatGroupHeader(yesterday, now, locale, tz)
        )
    }

    @Test
    fun `formatGroupHeader falls back to long date for older entries`() {
        val now = millisOf(2026, Calendar.MAY, 4, 8, 11)
        val older = millisOf(2026, Calendar.MAY, 1, 11, 30)
        assertEquals(
            "May 1, 2026",
            HistoryRowFormatters.formatGroupHeader(older, now, locale, tz)
        )
    }

    @Test
    fun `formatGroupHeader honors year boundary`() {
        val now = millisOf(2026, Calendar.JANUARY, 1, 0, 30)
        val newYearsEve = millisOf(2025, Calendar.DECEMBER, 31, 23, 30)
        assertEquals(
            "Yesterday",
            HistoryRowFormatters.formatGroupHeader(newYearsEve, now, locale, tz)
        )
        val twoYearsAgo = millisOf(2024, Calendar.DECEMBER, 31, 12, 0)
        assertEquals(
            "December 31, 2024",
            HistoryRowFormatters.formatGroupHeader(twoYearsAgo, now, locale, tz)
        )
    }

    // ─── formatSize ────────────────────────────────────────────────

    @Test
    fun `formatSize renders sub-megabyte as KB`() {
        assertEquals("812 KB", HistoryRowFormatters.formatSize(831_488L)) // ~812 KB
    }

    @Test
    fun `formatSize renders megabytes with one decimal`() {
        assertEquals("82.4 MB", HistoryRowFormatters.formatSize(86_405_120L))
    }

    @Test
    fun `formatSize renders gigabytes with two decimals`() {
        assertEquals("1.05 GB", HistoryRowFormatters.formatSize(1_127_428_915L))
    }

    @Test
    fun `formatSize clamps negative input to zero KB`() {
        assertEquals("0 KB", HistoryRowFormatters.formatSize(-100L))
    }

    // ─── formatRowAccessibility ────────────────────────────────────

    @Test
    fun `formatRowAccessibility leads with human date-time`() {
        val a = HistoryRowFormatters.formatRowAccessibility(
            primaryDateTime = "May 4 · 2:22 PM",
            sizeBytes = 86_405_120L,
            quality = "FHD"
        )
        assertEquals(
            "Recording May 4 · 2:22 PM, quality FHD, size 82.4 MB",
            a
        )
    }

    @Test
    fun `formatRowAccessibility includes duration when provided`() {
        val a = HistoryRowFormatters.formatRowAccessibility(
            primaryDateTime = "May 4 · 2:22 PM",
            sizeBytes = 86_405_120L,
            quality = "FHD",
            durationLabel = "1m 30s"
        )
        assertEquals(
            "Recording May 4 · 2:22 PM, quality FHD, duration 1m 30s, size 82.4 MB",
            a
        )
    }

    // ─── formatMoreActionsLabel ────────────────────────────────────

    @Test
    fun `formatMoreActionsLabel mirrors mockup copy`() {
        assertEquals(
            "More actions for May 4 · 2:22 PM recording",
            HistoryRowFormatters.formatMoreActionsLabel("May 4 · 2:22 PM")
        )
    }

    // ─── formatRetentionPill ───────────────────────────────────────

    @Test
    fun `formatRetentionPill is null when auto-delete disabled`() {
        assertNull(HistoryRowFormatters.formatRetentionPill(autoDeleteEnabled = false, keepLatest = 25))
    }

    @Test
    fun `formatRetentionPill is null when keepLatest is non-positive`() {
        assertNull(HistoryRowFormatters.formatRetentionPill(autoDeleteEnabled = true, keepLatest = 0))
        assertNull(HistoryRowFormatters.formatRetentionPill(autoDeleteEnabled = true, keepLatest = -3))
    }

    @Test
    fun `formatRetentionPill carries the keep-latest count`() {
        assertEquals(
            "Auto-keep latest 25",
            HistoryRowFormatters.formatRetentionPill(autoDeleteEnabled = true, keepLatest = 25)
        )
    }

    // ─── formatLibrarySummary ──────────────────────────────────────

    @Test
    fun `formatLibrarySummary handles singular`() {
        assertEquals(
            "1 recording · 82.4 MB",
            HistoryRowFormatters.formatLibrarySummary(1, 86_405_120L)
        )
    }

    @Test
    fun `formatLibrarySummary handles plural`() {
        assertEquals(
            "7 recordings · 412.0 MB",
            HistoryRowFormatters.formatLibrarySummary(7, 432_013_312L)
        )
    }

    @Test
    fun `formatLibrarySummary handles zero recordings`() {
        assertEquals(
            "0 recordings · 0 KB",
            HistoryRowFormatters.formatLibrarySummary(0, 0L)
        )
    }
}
