package com.aritr.rova.ui.signals

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 3.3 (NEW_UI_BACKEND_REPLAN §5 row 3.3) — typed snapshot of
 * the device power profile.
 *
 *  - [percent]: 0..100 when the platform reports a valid capacity;
 *    `null` when [BatteryManager.getIntProperty] returns an
 *    out-of-range sentinel (e.g. `Integer.MIN_VALUE` on emulators
 *    without a battery service, or hardware quirks). Consumers must
 *    treat `null` as "unknown" — NOT as zero.
 *  - [charging]: `true` when the platform reports
 *    [BatteryManager.BATTERY_STATUS_CHARGING] OR
 *    [BatteryManager.BATTERY_STATUS_FULL]. `FULL` is included because
 *    a device at 100% with the charger still plugged in is materially
 *    different from one running on battery — the WarningCenter
 *    suppresses low-battery warnings in either case.
 *  - [powerSaveMode]: mirrors [PowerManager.isPowerSaveMode] (battery
 *    saver). Independent of [percent] / [charging] — power-save can
 *    be toggled manually by the user at any battery level.
 */
data class PowerState(
    val percent: Int?,
    val charging: Boolean,
    val powerSaveMode: Boolean
)

/**
 * Phase 3.3 (NEW_UI_BACKEND_REPLAN §5 row 3.3) — leaf signal exposing
 * the device's current power profile as a [StateFlow]. The Phase 4
 * WarningCenterViewModel is the consumer; this slice ships the SIGNAL
 * ONLY (no [android.content.BroadcastReceiver] for
 * `ACTION_BATTERY_CHANGED` / `ACTION_BATTERY_LOW`, no banner, no
 * recording-start gate).
 *
 * SDK profile (minSdk = 24):
 *  - [BatteryManager.BATTERY_PROPERTY_CAPACITY] — API 21 (no gate)
 *  - [PowerManager.isPowerSaveMode] — API 21 (no gate)
 *  - [BatteryManager.BATTERY_PROPERTY_STATUS] — **API 26**
 * The `STATUS` property requires an inner SDK guard local to its
 * reference (lint flow analysis cannot trace through stored lambdas);
 * on API 24/25 the read short-circuits to [Int.MIN_VALUE], which the
 * `charging` predicate rejects (falls through to `false`). This is a
 * deliberate degradation: pre-Oreo callers cannot distinguish charging
 * from on-battery via `getIntProperty`, and the legacy
 * `ACTION_BATTERY_CHANGED` sticky-broadcast path is out of scope for
 * this slice (no `BroadcastReceiver`). Phase 4 banner-precedence may
 * suppress low-battery warnings on unknown-charging devices.
 *
 * No `sdkInt` ctor param: the seam contract is the typed snapshot, not
 * the SDK gate. The gate lives in [forContext] alongside the system
 * service refs (same shape as
 * [com.aritr.rova.ui.signals.ThermalStatusSignal.forContext] and
 * [com.aritr.rova.ui.signals.NotificationPermissionSignal.forContext]).
 *
 * Refresh contract: callers re-call [refresh] from the host Activity's
 * [androidx.lifecycle.Lifecycle.Event.ON_RESUME]. Idempotent —
 * [MutableStateFlow] dedupes equal [PowerState] values via the
 * data-class `equals`, so repeat calls under an unchanged profile
 * produce no spurious emission.
 *
 * Testability: a SINGLE composite seam [currentSnapshot] — the public
 * state type IS the same shape as the read, so a per-field tuple seam
 * would just duplicate the data class for no benefit. This is an
 * intentional departure from the raw-primitive seam used in
 * [com.aritr.rova.ui.signals.NotificationPermissionSignal] (`isGranted`)
 * and [com.aritr.rova.ui.signals.ThermalStatusSignal] (`currentStatus`).
 * Production callers use [forContext].
 */
class PowerSignal(
    private val currentSnapshot: () -> PowerState
) {

    private val _state: MutableStateFlow<PowerState> = MutableStateFlow(currentValue())
    val state: StateFlow<PowerState> = _state.asStateFlow()

    /**
     * Re-read the power profile and publish if changed. Call from
     * `ON_RESUME`. Idempotent on unchanged profile.
     */
    fun refresh() {
        _state.value = currentValue()
    }

    private fun currentValue(): PowerState = currentSnapshot()

    companion object {
        /**
         * Production constructor. Captures the application context in
         * the seam closure so the closure does not retain an Activity.
         *
         * `percent` rejection: [BatteryManager.getIntProperty] returns
         * `Integer.MIN_VALUE` when the property is unavailable; the
         * `takeIf { it in 0..100 }` predicate maps that — and any
         * future out-of-range sentinel — to `null`.
         *
         * `statusRaw` SDK guard: see class KDoc.
         */
        fun forContext(context: Context): PowerSignal {
            val app = context.applicationContext
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            return PowerSignal(
                currentSnapshot = {
                    val rawPercent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val percent = rawPercent.takeIf { it in 0..100 }
                    // Inner SDK guard local to the API-26 constant ref —
                    // lint flow analysis cannot follow the gate through
                    // a stored lambda. Same shape as
                    // NotificationPermissionSignal / ThermalStatusSignal.
                    val statusRaw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                    } else {
                        Int.MIN_VALUE
                    }
                    val charging = statusRaw == BatteryManager.BATTERY_STATUS_CHARGING ||
                        statusRaw == BatteryManager.BATTERY_STATUS_FULL
                    PowerState(
                        percent = percent,
                        charging = charging,
                        powerSaveMode = pm.isPowerSaveMode
                    )
                }
            )
        }
    }
}
