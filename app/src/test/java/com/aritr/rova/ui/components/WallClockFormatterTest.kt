package com.aritr.rova.ui.components

import com.aritr.rova.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class WallClockFormatterTest {
    private val utc = TimeZone.getTimeZone("UTC")
    // 2023-11-14T22:13:20Z = 1_700_000_000_000
    private val t = 1_700_000_000_000L

    @Test fun `24h time of day`() {
        assertEquals("22:13:20", RecordHudFormatters.formatTimeOfDay(t, utc, Locale.US, is24h = true, withDate = false))
    }
    @Test fun `12h time of day`() {
        assertEquals("10:13:20 PM", RecordHudFormatters.formatTimeOfDay(t, utc, Locale.US, is24h = false, withDate = false))
    }
    @Test fun `date prefix when withDate`() {
        assertEquals("Tue 22:13:20", RecordHudFormatters.formatTimeOfDay(t, utc, Locale.US, is24h = true, withDate = true))
    }
    @Test fun `timezone offset applied`() {
        val ny = TimeZone.getTimeZone("America/New_York") // EST -5 in Nov
        assertEquals("17:13:20", RecordHudFormatters.formatTimeOfDay(t, ny, Locale.US, is24h = true, withDate = false))
    }
    @Test fun `gap minutes`() {
        val ui = RecordHudFormatters.formatWallClockGap(900_000L) as UiText.StrArgs
        assertEquals(com.aritr.rova.R.string.player_wallclock_gap_minutes, ui.id)
        assertEquals(listOf(15), ui.args)
    }
    @Test fun `gap seconds under a minute`() {
        val ui = RecordHudFormatters.formatWallClockGap(45_000L) as UiText.StrArgs
        assertEquals(com.aritr.rova.R.string.player_wallclock_gap_seconds, ui.id)
        assertEquals(listOf(45), ui.args)
    }
}
