package com.aritr.rova.service.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aritr.rova.utils.RovaLog

/**
 * Re-arms the daily-window alarms after reboot / app replace (ADR-0027). Alarms
 * do not survive reboot. MUST only re-arm — starting a camera FGS from a
 * BOOT_COMPLETED receiver is forbidden on Android 15+. Enforced by
 * checkScheduleReceiverNoFgsStart.
 */
class ScheduleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                RovaLog.d { "ScheduleBootReceiver: ${intent.action} — re-arming window alarms" }
                ScheduleController.reschedule(context)
            }
        }
    }
}
