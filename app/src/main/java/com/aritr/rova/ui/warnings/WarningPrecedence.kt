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
     * current snapshot of all 21 rows (13 source signals after B4b ADR-0024).
     * [storageLowMidRec] was the last param with a `= false` default;
     * [saveFolderUnavailable] is appended last so existing positional call
     * sites remain unambiguous.
     *
     * Delegates to [allActive] and returns `firstOrNull()` — the single
     * highest-priority id, or `null` if none is active. Invariant:
     * `resolve(...) == allActive(...).firstOrNull()` for every input.
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
        cantMergeActive: Boolean = false,              // ← NEW (Phase 4.3 — recovery merge pre-flight failed)
        saveFolderUnavailable: Boolean = false,        // ← NEW (B4b ADR-0024 — custom save folder unusable)
        suppressBatteryCard: Boolean = false,          // ← NEW (once-per-24h rate-limit; decided once per session before the timestamp write)
    ): WarningId? = allActive(
        cameraPermissionGranted, exactAlarmGranted, storageInsufficient,
        thermal, power, camera, microphonePermissionGranted,
        notificationsGranted, batteryOptimizationExempt, storageLowMidRec,
        autoStopEcho, cantMergeActive, saveFolderUnavailable, suppressBatteryCard,
    ).firstOrNull()

    /**
     * Phase 4.2 — emit every currently-active [WarningId] ordinal-sorted.
     * The single condition-wiring source: [resolve] returns `firstOrNull()`
     * of this list. Pure function — no flow plumbing.
     */
    fun allActive(
        cameraPermissionGranted: Boolean,
        exactAlarmGranted: Boolean,
        storageInsufficient: Boolean,
        thermal: ThermalStatus,
        power: PowerState,
        camera: CameraSignalState,
        microphonePermissionGranted: Boolean,
        notificationsGranted: Boolean,
        batteryOptimizationExempt: Boolean,
        storageLowMidRec: Boolean = false,
        autoStopEcho: TerminalEcho? = null,
        cantMergeActive: Boolean = false,
        saveFolderUnavailable: Boolean = false,        // ← NEW (B4b ADR-0024 — custom save folder unusable)
        suppressBatteryCard: Boolean = false,          // ← NEW (once-per-24h rate-limit; see shouldSuppressBatteryCard)
    ): List<WarningId> {
        val result = mutableListOf<WarningId>()
        if (!cameraPermissionGranted) result += WarningId.CAMERA_PERMISSION_DENIED          // #1
        if (!exactAlarmGranted) result += WarningId.EXACT_ALARM_DENIED                      // #2
        if (storageInsufficient) result += WarningId.STORAGE_INSUFFICIENT                   // #3
        when (thermal) {                                                                     // #4 / #5 / #6 (mutually exclusive)
            ThermalStatus.SHUTDOWN -> result += WarningId.THERMAL_SHUTDOWN
            ThermalStatus.EMERGENCY -> result += WarningId.THERMAL_EMERGENCY
            ThermalStatus.CRITICAL -> result += WarningId.THERMAL_CRITICAL
            else -> Unit
        }
        val pct = power.percent
        if (pct != null && pct < 5 && !power.charging) result += WarningId.BATTERY_CRITICAL // #7
        when (camera) {                                                                      // #8 / #9 (mutually exclusive)
            CameraSignalState.IN_USE -> result += WarningId.CAMERA_IN_USE
            CameraSignalState.DISABLED -> result += WarningId.CAMERA_DISABLED
            else -> Unit
        }
        // #10 — skip low when critical already emitted (pct < 5 implies pct < 15; emit only critical)
        if (pct != null && pct >= 5 && pct < 15 && !power.charging) result += WarningId.BATTERY_LOW
        if (storageLowMidRec) result += WarningId.STORAGE_LOW_MID_REC                      // #11
        // #12-13 — auto-stop echoes. Slice 2: LOW_STORAGE → STORAGE_FULL_AUTOSTOPPED.
        // Slice 3: THERMAL → THERMAL_AUTOSTOPPED. Other StopReasons (USER,
        // PERMISSION_REVOKED, INIT_FAILED, NONE) do not yield an echo banner —
        // a user-driven stop is not a surprise to surface, and the other
        // reasons either pre-empt the start path or have no banner contract.
        autoStopEcho?.let { echo ->
            when (echo.stopReason) {
                StopReason.LOW_STORAGE -> result += WarningId.STORAGE_FULL_AUTOSTOPPED
                StopReason.THERMAL -> result += WarningId.THERMAL_AUTOSTOPPED
                StopReason.USER, StopReason.PERMISSION_REVOKED,
                StopReason.INIT_FAILED, StopReason.NONE -> Unit
            }
        }
        if (cantMergeActive) result += WarningId.CANT_MERGE                                // #14
        if (thermal == ThermalStatus.SEVERE) result += WarningId.THERMAL_SEVERE             // #15
        if (!microphonePermissionGranted) result += WarningId.MICROPHONE_DENIED             // #16
        if (!batteryOptimizationExempt && !suppressBatteryCard) result += WarningId.BATTERY_OPTIMIZATION_ON  // #17 (once-per-24h gate)
        if (power.powerSaveMode) result += WarningId.POWER_SAVE_MODE                        // #18
        if (thermal == ThermalStatus.MODERATE) result += WarningId.THERMAL_MODERATE        // #19
        if (!notificationsGranted) result += WarningId.NOTIFICATIONS_DENIED                // #20
        if (saveFolderUnavailable) result += WarningId.SAVE_FOLDER_UNAVAILABLE             // #21 (B4b ADR-0024)
        return result
    }
}
