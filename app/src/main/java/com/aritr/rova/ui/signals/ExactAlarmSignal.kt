package com.aritr.rova.ui.signals

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 3.6 (NEW_UI_BACKEND_REPLAN §5 row 3.6) — leaf signal exposing
 * the device's current `SCHEDULE_EXACT_ALARM` grant state as a
 * [StateFlow]. The Phase 4 WarningCenterViewModel is the consumer;
 * this slice ships the SIGNAL ONLY.
 *
 * SDK gate: pre-API-31 (S), exact-alarm revocation does not exist —
 * [AlarmManager.canScheduleExactAlarms] was added in S, and pre-S
 * exact alarms are unconditionally granted. The StateFlow value is
 * constant `true` below API 31.
 *
 * ADR 0001 reference: SCHEDULE_EXACT_ALARM is declared in the
 * manifest (minSdkVersion="31"). The degradation path —
 * setAndAllowWhileIdle inexact fallback when grant is denied — lives
 * in the scheduler / RovaController and is OUT OF SCOPE for this
 * slice. 3.6 is the read-side signal only.
 *
 * Refresh contract: API 31+ the OS broadcasts
 * [AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED]
 * when the user toggles the permission in Settings. [RovaApp.onCreate]
 * registers a process-lifetime BroadcastReceiver that calls [refresh]
 * on every such broadcast. Idempotent — [MutableStateFlow] dedupes
 * equal values, so repeat refreshes with the same grant produce no
 * spurious emission.
 *
 * Testability: [sdkInt] and [canScheduleExactAlarms] are constructor
 * seams so the unit test never touches a real [Context] /
 * [AlarmManager] — same shape as
 * [com.aritr.rova.ui.signals.NotificationPermissionSignal]
 * (`isGranted`) and
 * [com.aritr.rova.ui.signals.ThermalStatusSignal] (`currentStatus`).
 * Production callers use [forContext].
 */
class ExactAlarmSignal(
    private val sdkInt: Int,
    private val canScheduleExactAlarms: () -> Boolean
) {

    private val _state: MutableStateFlow<Boolean> = MutableStateFlow(currentValue())
    val state: StateFlow<Boolean> = _state.asStateFlow()

    /**
     * Re-read the exact-alarm grant and publish if changed. Invoked
     * by [RovaApp]'s
     * [AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED]
     * receiver. Idempotent on unchanged grant.
     */
    fun refresh() {
        _state.value = currentValue()
    }

    private fun currentValue(): Boolean =
        if (sdkInt < Build.VERSION_CODES.S) true else canScheduleExactAlarms()

    companion object {
        /**
         * Production constructor. Captures the application context in
         * the seam closure so the closure does not retain an Activity.
         * Reads [Build.VERSION.SDK_INT] once — SDK does not regress
         * for a running process.
         */
        fun forContext(context: Context): ExactAlarmSignal {
            val app = context.applicationContext
            val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return ExactAlarmSignal(
                sdkInt = Build.VERSION.SDK_INT,
                // Inner SDK guard mirrors `currentValue()` — logically
                // redundant (the seam is not invoked when sdkInt < S)
                // but local to the API-31 method reference so lint's
                // flow analysis suppresses NewApi for
                // `AlarmManager.canScheduleExactAlarms`. Same shape
                // as NotificationPermissionSignal.forContext.
                canScheduleExactAlarms = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        alarmManager.canScheduleExactAlarms()
                    } else {
                        true
                    }
                }
            )
        }
    }
}
