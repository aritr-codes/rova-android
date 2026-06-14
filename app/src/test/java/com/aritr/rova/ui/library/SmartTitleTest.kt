package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class SmartTitleTest {

    private val locale = Locale.US
    private val tz = TimeZone.getTimeZone("UTC")

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance(tz, locale); c.clear(); c.set(y, mo, d, h, mi, 0)
        c.set(Calendar.MILLISECOND, 0); return c.timeInMillis
    }

    @Test
    fun `derives day time clips and duration`() {
        val t = millis(2026, Calendar.JUNE, 14, 14, 32) // Sunday
        assertEquals(
            "Sun · 2:32 PM · 8 clips · 12m",
            SmartTitle.derive(t, segmentCount = 8, totalDurationMs = 12 * 60_000L, locale, tz)
        )
    }

    @Test
    fun `single clip is not pluralized and seconds shown under a minute`() {
        val t = millis(2026, Calendar.JUNE, 14, 9, 5)
        assertEquals(
            "Sun · 9:05 AM · 1 clip · 42s",
            SmartTitle.derive(t, segmentCount = 1, totalDurationMs = 42_000L, locale, tz)
        )
    }

    @Test
    fun `minutes and seconds combine above a minute`() {
        val t = millis(2026, Calendar.JUNE, 14, 9, 5)
        assertEquals(
            "Sun · 9:05 AM · 2 clips · 1m 30s",
            SmartTitle.derive(t, segmentCount = 2, totalDurationMs = 90_000L, locale, tz)
        )
    }
}
