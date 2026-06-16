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
    fun `title is a concise day and time name`() {
        val t = millis(2026, Calendar.JUNE, 14, 14, 32) // Sunday
        assertEquals("Sun · 2:32 PM", SmartTitle.derive(t, locale, tz))
    }

    @Test
    fun `am time formats with leading hour`() {
        val t = millis(2026, Calendar.JUNE, 14, 9, 5)
        assertEquals("Sun · 9:05 AM", SmartTitle.derive(t, locale, tz))
    }

    @Test
    fun `duration label is independent of the title`() {
        assertEquals("42s", SmartTitle.durationLabel(42_000L))
        assertEquals("12m", SmartTitle.durationLabel(12 * 60_000L))
        assertEquals("1m 30s", SmartTitle.durationLabel(90_000L))
    }
}
