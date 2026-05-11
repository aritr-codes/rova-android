package com.aritr.rova.ui.signals

import android.content.Context
import com.aritr.rova.ui.screens.BatteryOptimizationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 4.1 (NEW_UI_BACKEND_REPLAN §5 row 13 — "move into WarningCenter") —
 * leaf signal exposing whether this app is currently EXEMPT from battery
 * optimization (the Doze allowlist) as a [StateFlow]. `true` = exempt =
 * good — the WarningCenter raises no battery-optimization banner.
 *
 * No SDK gate: [BatteryOptimizationHelper.isIgnoring] owns the platform
 * read (`PowerManager.isIgnoringBatteryOptimizations`, API 23; minSdk is
 * 24, so always available).
 *
 * Refresh contract: the exemption grant does NOT fire a system broadcast.
 * Callers re-call [refresh] from the host Activity's
 * [androidx.lifecycle.Lifecycle.Event.ON_RESUME]. Idempotent —
 * [MutableStateFlow] dedupes equal values.
 *
 * Testability: [isExemptNow] is a constructor seam so the unit test never
 * touches a real [Context] — same shape as [NotificationPermissionSignal]
 * (`isGranted`). Production callers use [forContext].
 */
class BatteryOptimizationSignal(
    private val isExemptNow: () -> Boolean
) {
    private val _isExempt: MutableStateFlow<Boolean> = MutableStateFlow(isExemptNow())
    val isExempt: StateFlow<Boolean> = _isExempt.asStateFlow()

    /** Re-read the exemption state and publish if changed. Call from ON_RESUME. */
    fun refresh() { _isExempt.value = isExemptNow() }

    companion object {
        /**
         * Production constructor. Captures the application context in the
         * seam closure so the closure does not retain an Activity.
         */
        fun forContext(context: Context): BatteryOptimizationSignal {
            val app = context.applicationContext
            return BatteryOptimizationSignal(isExemptNow = { BatteryOptimizationHelper.isIgnoring(app) })
        }
    }
}
