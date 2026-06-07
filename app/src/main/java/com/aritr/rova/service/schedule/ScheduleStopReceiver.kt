package com.aritr.rova.service.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aritr.rova.RovaApp
import com.aritr.rova.utils.RovaLog

/**
 * Window-close alarm (ADR-0027). Stopping a running FGS from the background IS
 * permitted, so this leg is fully silent. Delegates to RovaApp, which forwards a
 * cooperative stop with reason=SCHEDULE_WINDOW to the live in-process controller
 * (no new stop pathway — keeps checkStopNoGetService / checkUserStoppedBeforeMerge
 * intact). If the process is dead there is nothing recording to stop; cold-launch
 * recovery classifies from evidence (ADR-0027 §6). NEVER starts the FGS.
 */
class ScheduleStopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WINDOW_STOP) return
        RovaLog.d("ScheduleStopReceiver: window close — requesting scheduled stop")
        (context.applicationContext as RovaApp).requestScheduleWindowStop()
        // Re-arm the next occurrence's start/stop alongside.
        ScheduleController.reschedule(context)
    }

    companion object {
        const val ACTION_WINDOW_STOP = "com.aritr.rova.action.SCHEDULE_WINDOW_STOP"
    }
}
