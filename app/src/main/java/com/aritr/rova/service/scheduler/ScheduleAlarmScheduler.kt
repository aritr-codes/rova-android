package com.aritr.rova.service.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.aritr.rova.service.schedule.ScheduleStartReceiver
import com.aritr.rova.service.schedule.ScheduleStopReceiver
import com.aritr.rova.utils.RovaLog

/**
 * Arms the daily-window START and STOP alarms (ADR-0027). Stateless, like
 * [AlarmScheduler]: every arm/cancel reconstructs the same PendingIntent shape
 * (identity in `Intent.data`), so it is deterministic across process death.
 *
 * Exact-alarm discipline (ADR-0001 / ADR-0027): API < 31 may use
 * setExactAndAllowWhileIdle freely; API >= 31 must consult
 * canScheduleExactAlarms() AND wrap the call in try/catch SecurityException
 * (check-to-call TOCTOU), degrading to inexact setWindow. NEVER getService —
 * targets are BroadcastReceivers (enforced by checkSchedulerNoGetService).
 */
object ScheduleAlarmScheduler {

    private const val REQUEST_CODE = 0
    private const val URI_SCHEME = "rova"
    private const val URI_HOST = "schedule"
    private const val INEXACT_WINDOW_MS = 60_000L

    enum class Leg(val path: String) { START("start"), STOP("stop") }

    fun armStart(context: Context, triggerAtMillis: Long) =
        arm(context, Leg.START, triggerAtMillis, ScheduleStartReceiver::class.java, ScheduleStartReceiver.ACTION_WINDOW_START)

    fun armStop(context: Context, triggerAtMillis: Long) =
        arm(context, Leg.STOP, triggerAtMillis, ScheduleStopReceiver::class.java, ScheduleStopReceiver.ACTION_WINDOW_STOP)

    fun cancelAll(context: Context) {
        cancel(context, Leg.START, ScheduleStartReceiver::class.java, ScheduleStartReceiver.ACTION_WINDOW_START)
        cancel(context, Leg.STOP, ScheduleStopReceiver::class.java, ScheduleStopReceiver.ACTION_WINDOW_STOP)
    }

    private fun arm(context: Context, leg: Leg, triggerAtMillis: Long, cls: Class<*>, action: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, leg, cls, action, mutateExisting = true)!!
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                RovaLog.d("ScheduleAlarmScheduler.arm(${leg.path}, t=$triggerAtMillis) [exact]")
                return
            } catch (se: SecurityException) {
                RovaLog.w("ScheduleAlarmScheduler.arm(${leg.path}): exact denied at call site; inexact", se)
            }
        }
        am.setWindow(AlarmManager.RTC_WAKEUP, triggerAtMillis, INEXACT_WINDOW_MS, pi)
        RovaLog.d("ScheduleAlarmScheduler.arm(${leg.path}, t=$triggerAtMillis) [inexact]")
    }

    private fun cancel(context: Context, leg: Leg, cls: Class<*>, action: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, leg, cls, action, mutateExisting = false) ?: return
        am.cancel(pi)
        pi.cancel()
        RovaLog.d("ScheduleAlarmScheduler.cancel(${leg.path}): cleared")
    }

    private fun pendingIntent(context: Context, leg: Leg, cls: Class<*>, action: String, mutateExisting: Boolean): PendingIntent? {
        val intent = Intent(context, cls).apply {
            this.action = action
            data = Uri.Builder().scheme(URI_SCHEME).authority(URI_HOST).appendPath(leg.path).build()
        }
        val flags = if (mutateExisting) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        }
        // getBroadcast, never getService — checkScheduleReceiverNoFgsStart /
        // checkSchedulerNoGetService enforce.
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
