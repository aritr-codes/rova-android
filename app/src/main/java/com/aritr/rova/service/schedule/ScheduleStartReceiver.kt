package com.aritr.rova.service.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aritr.rova.MainActivity
import com.aritr.rova.R
import com.aritr.rova.utils.RovaLog

/**
 * Window-open alarm (ADR-0027). Android 14+ forbids starting a camera-type FGS
 * from the background, so this receiver does NOT start recording: it posts a
 * high-importance "tap to record" notification (whose tap brings MainActivity
 * to the foreground — the one legal camera-start site) and re-arms the next
 * day's window. NEVER uses getService / startForegroundService — enforced by
 * checkScheduleReceiverNoFgsStart.
 */
class ScheduleStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WINDOW_START) return
        postStartPrompt(context)
        // Re-arm the next occurrence (alarms are one-shot).
        ScheduleController.reschedule(context)
    }

    private fun postStartPrompt(context: Context) {
        val app = context.applicationContext
        ensureChannel(app)
        val tapIntent = Intent(app, MainActivity::class.java).apply {
            action = MainActivity.ACTION_SCHEDULE_AUTO_ARM
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            app, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(app.getString(R.string.schedule_start_notif_title))
            .setContentText(app.getString(R.string.schedule_start_notif_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        // POST_NOTIFICATIONS may be denied (API 33+); NotificationManagerCompat
        // no-ops safely. The Settings toggle requests it + warns when denied.
        try {
            NotificationManagerCompat.from(app).notify(NOTIF_ID, notif)
        } catch (se: SecurityException) {
            RovaLog.w("ScheduleStartReceiver: notify denied", se)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.schedule_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.schedule_channel_desc) },
        )
    }

    companion object {
        const val ACTION_WINDOW_START = "com.aritr.rova.action.SCHEDULE_WINDOW_START"
        const val CHANNEL_ID = "rova_schedule"
        private const val NOTIF_ID = 4201
    }
}
