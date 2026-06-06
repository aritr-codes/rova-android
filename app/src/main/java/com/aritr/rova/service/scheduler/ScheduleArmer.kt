package com.aritr.rova.service.scheduler

import java.util.Calendar
import java.util.TimeZone

/**
 * Immutable snapshot of the user's daily-window settings, decoupled from
 * [com.aritr.rova.data.RovaSettings] so [ScheduleArmer] stays pure (no Android).
 *
 * @property startMinuteOfDay 0..1439 (minutes past local midnight).
 * @property stopMinuteOfDay  0..1439. stop <= start ⇒ window ends next day (overnight).
 * @property weekdayMask bit i set ⇒ that weekday is eligible, where
 *   bit 0 = Monday .. bit 6 = Sunday. 0 ⇒ every day.
 */
data class ScheduleSettingsSnapshot(
    val enabled: Boolean,
    val startMinuteOfDay: Int,
    val stopMinuteOfDay: Int,
    val weekdayMask: Int,
)

/** The next armed window: absolute start/stop instants (epoch millis, UTC). */
data class ScheduleArming(
    val startAtMillis: Long,
    val stopAtMillis: Long,
)

/**
 * Pure computation of the next daily-window start/stop instants. No Android
 * types — unit-tested under `isReturnDefaultValues = true` (house pure-helper
 * pattern, ADR-0027). Uses [Calendar] (not java.time) so it is minSdk-24-safe
 * without core-library desugaring.
 */
object ScheduleArmer {

    /**
     * @return the next [ScheduleArming] strictly after [nowMillis] in [zone],
     *   or null if scheduling is disabled. "Strictly after" means: if the
     *   device is already inside a window, the NEXT occurrence is armed (the
     *   current window is never retro-fired).
     */
    fun computeNext(nowMillis: Long, zone: TimeZone, settings: ScheduleSettingsSnapshot): ScheduleArming? {
        if (!settings.enabled) return null
        val startAt = nextStart(nowMillis, zone, settings) ?: return null
        val stopAt = stopForStart(startAt, zone, settings)
        return ScheduleArming(startAt, stopAt)
    }

    /**
     * If [nowMillis] falls inside an eligible window, returns that window's stop
     * instant; otherwise null. Used by the one-tap start path (ADR-0027) to tell
     * the service when to self-heal. Checks the window starting today (offset 0)
     * and the one starting yesterday (offset -1, still open for overnight
     * windows). A window's weekday eligibility is keyed to its START day.
     */
    fun currentWindowEnd(nowMillis: Long, zone: TimeZone, settings: ScheduleSettingsSnapshot): Long? {
        if (!settings.enabled) return null
        for (dayOffset in 0 downTo -1) {
            val startCal = Calendar.getInstance(zone).apply {
                timeInMillis = nowMillis
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, settings.startMinuteOfDay / 60)
                set(Calendar.MINUTE, settings.startMinuteOfDay % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (!isEligible(startCal, settings.weekdayMask)) continue
            val startAt = startCal.timeInMillis
            val stopAt = stopForStart(startAt, zone, settings)
            if (nowMillis in startAt until stopAt) return stopAt
        }
        return null
    }

    private fun nextStart(now: Long, zone: TimeZone, s: ScheduleSettingsSnapshot): Long? {
        // 0..7 covers a full week + 1, so any set weekday is guaranteed hit.
        for (dayOffset in 0..7) {
            val cal = Calendar.getInstance(zone).apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, s.startMinuteOfDay / 60)
                set(Calendar.MINUTE, s.startMinuteOfDay % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val candidate = cal.timeInMillis
            if (candidate <= now) continue
            if (isEligible(cal, s.weekdayMask)) return candidate
        }
        return null
    }

    private fun stopForStart(startAt: Long, zone: TimeZone, s: ScheduleSettingsSnapshot): Long {
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = startAt
            set(Calendar.HOUR_OF_DAY, s.stopMinuteOfDay / 60)
            set(Calendar.MINUTE, s.stopMinuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // stop <= start on the same day ⇒ the window ends the following day.
        if (s.stopMinuteOfDay <= s.startMinuteOfDay) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun isEligible(cal: Calendar, mask: Int): Boolean {
        if (mask == 0) return true
        val bit = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6
            else -> return false
        }
        return (mask and (1 shl bit)) != 0
    }
}
