package com.aritr.rova.service.schedule

import android.content.Context
import com.aritr.rova.data.RovaSettings
import com.aritr.rova.service.scheduler.ScheduleAlarmScheduler
import com.aritr.rova.service.scheduler.ScheduleArmer
import com.aritr.rova.utils.RovaLog
import java.util.TimeZone

/**
 * Orchestrates the daily-window alarms (ADR-0027). Single entry point used by
 * the Settings toggle, the window-open re-arm, and the boot re-arm. Never starts
 * the camera FGS — that happens only from MainActivity on the user's
 * notification tap. The Android edges (clock, default zone, AlarmManager) live
 * here / in [ScheduleAlarmScheduler] so [ScheduleArmer] stays pure.
 */
object ScheduleController {

    /** Recompute from current settings and (re-)arm both legs, or cancel if disabled. */
    fun reschedule(context: Context) {
        val app = context.applicationContext
        val snapshot = RovaSettings(app).scheduleSnapshot()
        if (!snapshot.enabled) {
            ScheduleAlarmScheduler.cancelAll(app)
            RovaLog.d("ScheduleController.reschedule: disabled — alarms cancelled")
            return
        }
        val arming = ScheduleArmer.computeNext(System.currentTimeMillis(), TimeZone.getDefault(), snapshot)
        if (arming == null) {
            ScheduleAlarmScheduler.cancelAll(app)
            return
        }
        ScheduleAlarmScheduler.armStart(app, arming.startAtMillis)
        ScheduleAlarmScheduler.armStop(app, arming.stopAtMillis)
        RovaLog.d("ScheduleController.reschedule: armed start=${arming.startAtMillis} stop=${arming.stopAtMillis}")
    }

    /**
     * Re-arm at window-OPEN (called by [ScheduleStartReceiver] only). CRITICAL:
     * unlike [reschedule], this must NOT clobber the current window's STOP alarm
     * with the next window's. It keeps the just-opened window's close armed
     * (so the silent stop still fires) and advances only the START to the next
     * occurrence. The next window's STOP is armed later when the current STOP
     * fires (→ [reschedule]). If the open alarm fired so late the window already
     * closed, fall back to arming the next full window.
     */
    fun onWindowOpened(context: Context) {
        val app = context.applicationContext
        val snapshot = RovaSettings(app).scheduleSnapshot()
        if (!snapshot.enabled) {
            ScheduleAlarmScheduler.cancelAll(app)
            return
        }
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val currentEnd = ScheduleArmer.currentWindowEnd(now, tz, snapshot)
        val next = ScheduleArmer.computeNext(now, tz, snapshot)
        if (currentEnd != null) {
            ScheduleAlarmScheduler.armStop(app, currentEnd) // keep THIS window's close
            if (next != null) ScheduleAlarmScheduler.armStart(app, next.startAtMillis)
            RovaLog.d("ScheduleController.onWindowOpened: stop=$currentEnd nextStart=${next?.startAtMillis}")
        } else if (next != null) {
            // Window already closed (late alarm) — arm the next full window.
            ScheduleAlarmScheduler.armStart(app, next.startAtMillis)
            ScheduleAlarmScheduler.armStop(app, next.stopAtMillis)
        } else {
            ScheduleAlarmScheduler.cancelAll(app)
        }
    }

    fun cancel(context: Context) = ScheduleAlarmScheduler.cancelAll(context.applicationContext)
}
