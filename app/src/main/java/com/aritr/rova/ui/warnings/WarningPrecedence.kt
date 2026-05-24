package com.aritr.rova.ui.warnings

import com.aritr.rova.data.StopReason
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
 * As of Phase 4.1b all 16 rows are reachable. R2 (2026-05-13)
 * inserts #11 STORAGE_LOW_MID_REC; it becomes reachable when T5 wires
 * the 10th source flow into [resolve].
 *
 * Battery semantics: a low / critical battery warning fires only when the
 * percent is KNOWN (non-null) AND below the threshold AND not charging.
 * Unknown percent => no battery warning ([com.aritr.rova.ui.signals.PowerSignal]
 * returns a null percent on emulators / hardware quirks — never raise a
 * false alarm). `charging` is best-effort on API 24/25 (see PowerSignal
 * KDoc) — acceptable: a missed "low battery" nag pre-Oreo beats a
 * spurious one.
 *
 * Camera state: only IN_USE / DISABLED raise a banner. OK / UNKNOWN /
 * OTHER_ERROR raise nothing here — OTHER_ERROR routes to the Library
 * recovery card per the replan, not a Record-screen banner.
 *
 * Storage: [storageInsufficient] is the recording service's start-time
 * preflight, surfaced early ([com.aritr.rova.ui.signals.StorageSignal]).
 * [storageLowMidRec] is the mid-recording low-storage signal added in R2
 * (row #11, [com.aritr.rova.ui.signals.StorageLowMidRecSignal]).
 */
internal object WarningPrecedence {
    /**
     * Resolves the single highest-priority active [WarningId] from the
     * current snapshot of all 17 rows (10 source signals after R2 T5).
     * [storageLowMidRec] is the last param with a `= false` default so
     * existing positional call sites remain unambiguous.
     */
    fun resolve(
        cameraPermissionGranted: Boolean,
        exactAlarmGranted: Boolean,
        storageInsufficient: Boolean,
        thermal: ThermalStatus,
        power: PowerState,
        camera: CameraSignalState,
        microphonePermissionGranted: Boolean,
        notificationsGranted: Boolean,
        batteryOptimizationExempt: Boolean,
        storageLowMidRec: Boolean = false,             // ← NEW (last param, default = false to keep existing call sites compiling)
        autoStopEcho: TerminalEcho? = null,            // ← NEW (Phase 4 Slice 2)
    ): WarningId? {
        if (!cameraPermissionGranted) return WarningId.CAMERA_PERMISSION_DENIED            // #1
        if (!exactAlarmGranted) return WarningId.EXACT_ALARM_DENIED                         // #2
        if (storageInsufficient) return WarningId.STORAGE_INSUFFICIENT                      // #3
        when (thermal) {                                                                   // #4 / #5 / #6
            ThermalStatus.SHUTDOWN -> return WarningId.THERMAL_SHUTDOWN
            ThermalStatus.EMERGENCY -> return WarningId.THERMAL_EMERGENCY
            ThermalStatus.CRITICAL -> return WarningId.THERMAL_CRITICAL
            else -> Unit
        }
        val pct = power.percent
        if (pct != null && pct < 5 && !power.charging) return WarningId.BATTERY_CRITICAL   // #7
        when (camera) {                                                                    // #8 / #9
            CameraSignalState.IN_USE -> return WarningId.CAMERA_IN_USE
            CameraSignalState.DISABLED -> return WarningId.CAMERA_DISABLED
            else -> Unit
        }
        if (pct != null && pct < 15 && !power.charging) return WarningId.BATTERY_LOW        // #10
        if (storageLowMidRec) return WarningId.STORAGE_LOW_MID_REC                         // #11
        // #12 — STORAGE_FULL_AUTOSTOPPED (Phase 4 Slice 2; LOW_STORAGE-filtered echo of past auto-stop)
        autoStopEcho?.takeIf { it.stopReason == StopReason.LOW_STORAGE }
            ?.let { return WarningId.STORAGE_FULL_AUTOSTOPPED }
        if (thermal == ThermalStatus.SEVERE) return WarningId.THERMAL_SEVERE                // #13
        if (!microphonePermissionGranted) return WarningId.MICROPHONE_DENIED               // #14
        if (!batteryOptimizationExempt) return WarningId.BATTERY_OPTIMIZATION_ON           // #15
        if (power.powerSaveMode) return WarningId.POWER_SAVE_MODE                           // #16
        if (thermal == ThermalStatus.MODERATE) return WarningId.THERMAL_MODERATE           // #17
        if (!notificationsGranted) return WarningId.NOTIFICATIONS_DENIED                   // #18
        return null
    }
}
