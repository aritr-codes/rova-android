package com.aritr.rova.ui.warnings

import com.aritr.rova.ui.signals.CameraSignalState
import com.aritr.rova.ui.signals.PowerState
import com.aritr.rova.ui.signals.ThermalStatus

/**
 * Pure resolver: given the current state of the wired signals, returns
 * the single highest-priority active [WarningId], or `null` if none is
 * active. No Android, no coroutines, no flows — JVM-testable directly.
 *
 * "Highest priority" == lowest [WarningId.ordinal] among the active set,
 * realised as a straight walk of the rows in declaration order returning
 * the first whose predicate holds. This IS the "re-evaluate from current
 * signal state, no materialized queue" semantics (replan precedence
 * decision #5): there is no queue — the active warning follows the
 * signals directly, every recomputation.
 *
 * Rows #1 (camera-perm), #3 (storage-to-start), #12 (mic/video-only)
 * have no parameter here yet — their producers land in Phase 4.1b. Until
 * then this function never returns those three [WarningId] values.
 *
 * Battery semantics: a low / critical battery warning fires only when the
 * percent is KNOWN (non-null) AND below the threshold AND not charging.
 * Unknown percent → no battery warning ([com.aritr.rova.ui.signals.PowerSignal]
 * returns a null percent on emulators / hardware quirks — never raise a
 * false alarm). `charging` is best-effort on API 24/25 (see PowerSignal
 * KDoc) — that is acceptable: a missed "low battery" nag pre-Oreo beats
 * a spurious one.
 *
 * Camera: only IN_USE / DISABLED raise a banner. OK / UNKNOWN / OTHER_ERROR
 * raise nothing here — OTHER_ERROR routes to the Library recovery card
 * per the replan, not a Record-screen banner.
 */
internal object WarningPrecedence {
    fun resolve(
        exactAlarmGranted: Boolean,
        thermal: ThermalStatus,
        power: PowerState,
        camera: CameraSignalState,
        notificationsGranted: Boolean,
        batteryOptimizationExempt: Boolean
    ): WarningId? {
        if (!exactAlarmGranted) return WarningId.EXACT_ALARM_DENIED                       // #2
        when (thermal) {                                                                  // #4 / #5 / #6
            ThermalStatus.SHUTDOWN -> return WarningId.THERMAL_SHUTDOWN
            ThermalStatus.EMERGENCY -> return WarningId.THERMAL_EMERGENCY
            ThermalStatus.CRITICAL -> return WarningId.THERMAL_CRITICAL
            else -> Unit
        }
        val pct = power.percent
        if (pct != null && pct < 5 && !power.charging) return WarningId.BATTERY_CRITICAL  // #7
        when (camera) {                                                                   // #8 / #9
            CameraSignalState.IN_USE -> return WarningId.CAMERA_IN_USE
            CameraSignalState.DISABLED -> return WarningId.CAMERA_DISABLED
            else -> Unit
        }
        if (pct != null && pct < 15 && !power.charging) return WarningId.BATTERY_LOW       // #10
        if (thermal == ThermalStatus.SEVERE) return WarningId.THERMAL_SEVERE              // #11
        if (!batteryOptimizationExempt) return WarningId.BATTERY_OPTIMIZATION_ON          // #13
        if (power.powerSaveMode) return WarningId.POWER_SAVE_MODE                          // #14
        if (thermal == ThermalStatus.MODERATE) return WarningId.THERMAL_MODERATE          // #15
        if (!notificationsGranted) return WarningId.NOTIFICATIONS_DENIED                  // #16
        return null
    }
}
