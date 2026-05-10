package com.aritr.rova.ui.signals

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 3.4 (NEW_UI_BACKEND_REPLAN §5 row 3.4) — typed wrapper for
 * [PowerManager.getCurrentThermalStatus]. Order mirrors the
 * `PowerManager.THERMAL_STATUS_*` ints `0..6` so [fromRaw] is a
 * direct table lookup. Unknown ints map defensively to [NONE] —
 * a future OS may extend the range, and the signal must not crash.
 */
enum class ThermalStatus {
    NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN;

    companion object {
        fun fromRaw(raw: Int): ThermalStatus = when (raw) {
            0 -> NONE
            1 -> LIGHT
            2 -> MODERATE
            3 -> SEVERE
            4 -> CRITICAL
            5 -> EMERGENCY
            6 -> SHUTDOWN
            else -> NONE
        }
    }
}

/**
 * Phase 3.4 (NEW_UI_BACKEND_REPLAN §5 row 3.4) — leaf signal exposing
 * the device's current thermal status as a [StateFlow]. The Phase 4
 * WarningCenterViewModel is the consumer; this slice ships the SIGNAL
 * ONLY (no listener registration, no banner, no recording-start gate).
 *
 * SDK gate: pre-API-29, [PowerManager.getCurrentThermalStatus] does
 * not exist — StateFlow value is constant [ThermalStatus.NONE].
 *
 * Refresh contract: thermal-status changes do NOT fire a system
 * broadcast in this slice (a `OnThermalStatusChangedListener` is
 * Phase 4 territory). Callers re-call [refresh] from the host
 * Activity's [androidx.lifecycle.Lifecycle.Event.ON_RESUME].
 * Idempotent — [MutableStateFlow] dedupes equal values, so repeat
 * calls under the same status produce no spurious emission.
 *
 * Testability: [sdkInt] and [currentStatus] are constructor seams so
 * the unit test never touches a real [Context] / [PowerManager] —
 * same shape as
 * [com.aritr.rova.ui.signals.NotificationPermissionSignal]
 * (`isGranted`),
 * [com.aritr.rova.ui.screens.onboarding.OnboardingViewModel]
 * (`markCompleted`) and
 * [com.aritr.rova.ui.screens.player.PlayerStateEmitter] (`attach`).
 * Production callers use [forContext].
 */
class ThermalStatusSignal(
    private val sdkInt: Int,
    private val currentStatus: () -> Int
) {

    private val _state: MutableStateFlow<ThermalStatus> = MutableStateFlow(currentValue())
    val state: StateFlow<ThermalStatus> = _state.asStateFlow()

    /**
     * Re-read the thermal status and publish if changed. Call from
     * `ON_RESUME`. Idempotent on unchanged status.
     */
    fun refresh() {
        _state.value = currentValue()
    }

    private fun currentValue(): ThermalStatus =
        if (sdkInt < Build.VERSION_CODES.Q) ThermalStatus.NONE
        else ThermalStatus.fromRaw(currentStatus())

    companion object {
        /**
         * Production constructor. Captures the application context in
         * the seam closure so the closure does not retain an Activity.
         * Reads [Build.VERSION.SDK_INT] once — SDK does not regress
         * for a running process.
         */
        fun forContext(context: Context): ThermalStatusSignal {
            val app = context.applicationContext
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            return ThermalStatusSignal(
                sdkInt = Build.VERSION.SDK_INT,
                // Inner SDK guard mirrors `currentValue()` — logically
                // redundant (the seam is not invoked when sdkInt < Q)
                // but local to the API-29 method reference so lint's
                // flow analysis suppresses NewApi for
                // `PowerManager.getCurrentThermalStatus`. Same shape
                // as NotificationPermissionSignal.forContext.
                currentStatus = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        pm.currentThermalStatus
                    } else {
                        0
                    }
                }
            )
        }
    }
}
