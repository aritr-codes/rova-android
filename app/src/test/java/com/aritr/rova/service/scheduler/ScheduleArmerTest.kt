package com.aritr.rova.service.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScheduleArmerTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** Build an epoch-millis for a given Y/M/D h:m in [tz]. month is 1-based. */
    private fun at(tz: TimeZone, y: Int, mon: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance(tz).apply {
            clear()
            set(y, mon - 1, d, h, min, 0)
        }.timeInMillis

    private fun min(h: Int, m: Int) = h * 60 + m

    @Test
    fun disabled_returnsNull() {
        val s = ScheduleSettingsSnapshot(enabled = false, startMinuteOfDay = min(9, 0), stopMinuteOfDay = min(17, 0), weekdayMask = 0)
        assertNull(ScheduleArmer.computeNext(at(utc, 2026, 6, 10, 8, 0), utc, s))
    }

    @Test
    fun sameDayWindow_beforeStart_armsToday() {
        val s = ScheduleSettingsSnapshot(true, min(9, 0), min(17, 0), 0)
        val r = ScheduleArmer.computeNext(at(utc, 2026, 6, 10, 8, 0), utc, s)!!
        assertEquals(at(utc, 2026, 6, 10, 9, 0), r.startAtMillis)
        assertEquals(at(utc, 2026, 6, 10, 17, 0), r.stopAtMillis)
    }

    @Test
    fun insideWindow_armsNextDayStart() {
        val s = ScheduleSettingsSnapshot(true, min(9, 0), min(17, 0), 0)
        val r = ScheduleArmer.computeNext(at(utc, 2026, 6, 10, 12, 0), utc, s)!!
        assertEquals(at(utc, 2026, 6, 11, 9, 0), r.startAtMillis)
        assertEquals(at(utc, 2026, 6, 11, 17, 0), r.stopAtMillis)
    }

    @Test
    fun overnightWindow_stopIsNextDay() {
        val s = ScheduleSettingsSnapshot(true, min(22, 0), min(6, 0), 0)
        val r = ScheduleArmer.computeNext(at(utc, 2026, 6, 10, 20, 0), utc, s)!!
        assertEquals(at(utc, 2026, 6, 10, 22, 0), r.startAtMillis)
        assertEquals(at(utc, 2026, 6, 11, 6, 0), r.stopAtMillis)
    }

    @Test
    fun weekdayMask_skipsToNextEligibleDay() {
        val mask = (1 shl 0) or (1 shl 4)
        val s = ScheduleSettingsSnapshot(true, min(9, 0), min(17, 0), mask)
        val r = ScheduleArmer.computeNext(at(utc, 2026, 6, 10, 8, 0), utc, s)!!
        assertEquals(at(utc, 2026, 6, 12, 9, 0), r.startAtMillis)
    }

    @Test
    fun weekdayMask_wrapsToNextWeek() {
        val mask = (1 shl 0)
        val s = ScheduleSettingsSnapshot(true, min(9, 0), min(17, 0), mask)
        val r = ScheduleArmer.computeNext(at(utc, 2026, 6, 9, 10, 0), utc, s)!!
        assertEquals(at(utc, 2026, 6, 15, 9, 0), r.startAtMillis)
    }

    @Test
    fun atExactStartInstant_armsNextOccurrence() {
        val s = ScheduleSettingsSnapshot(true, min(9, 0), min(17, 0), 0)
        val r = ScheduleArmer.computeNext(at(utc, 2026, 6, 10, 9, 0), utc, s)!!
        assertEquals(at(utc, 2026, 6, 11, 9, 0), r.startAtMillis)
    }

    @Test
    fun dstSpringForward_startInstantIsValid() {
        val tz = TimeZone.getTimeZone("America/New_York")
        val s = ScheduleSettingsSnapshot(true, min(9, 0), min(17, 0), 0)
        val now = at(tz, 2026, 3, 8, 7, 0)
        val r = ScheduleArmer.computeNext(now, tz, s)!!
        assertEquals(at(tz, 2026, 3, 8, 9, 0), r.startAtMillis)
        assert(r.stopAtMillis > r.startAtMillis)
    }

    @Test
    fun equalStartStop_isFullDayWindow() {
        val s = ScheduleSettingsSnapshot(true, min(9, 0), min(9, 0), 0)
        val r = ScheduleArmer.computeNext(at(utc, 2026, 6, 10, 8, 0), utc, s)!!
        assertEquals(at(utc, 2026, 6, 10, 9, 0), r.startAtMillis)
        assertEquals(at(utc, 2026, 6, 11, 9, 0), r.stopAtMillis)
    }
}
