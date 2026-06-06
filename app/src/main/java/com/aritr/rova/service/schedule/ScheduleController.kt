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

    fun cancel(context: Context) = ScheduleAlarmScheduler.cancelAll(context.applicationContext)
}
