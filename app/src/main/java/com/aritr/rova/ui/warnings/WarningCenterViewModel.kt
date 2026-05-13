package com.aritr.rova.ui.warnings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aritr.rova.ui.signals.CameraSignalState
import com.aritr.rova.ui.signals.PowerState
import com.aritr.rova.ui.signals.ThermalStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Phase 4.1 / 4.1b / R2-T5 — the unified WarningCenter aggregator. Consumes
 * the ten wired leaf signals and exposes the single highest-priority active
 * warning as a [StateFlow]. ONE ViewModel — no per-category split
 * (WarningCenterContract NO-GO #8). It does NOT own the signals; it
 * observes them.
 *
 * The aggregation logic lives in the [Companion.aggregate] flow operator
 * so it is unit-testable with fake [kotlinx.coroutines.flow.MutableStateFlow]s
 * and no [viewModelScope] — the same posture as PlayerUriResolver /
 * SegmentedTimelineMath / WakeLockPolicy: the testable core is a plain
 * function, the ViewModel is a `stateIn` shell.
 */
class WarningCenterViewModel(
    cameraPermissionGranted: StateFlow<Boolean>,
    exactAlarmGranted: StateFlow<Boolean>,
    storageInsufficient: StateFlow<Boolean>,
    thermal: StateFlow<ThermalStatus>,
    power: StateFlow<PowerState>,
    camera: StateFlow<CameraSignalState>,
    microphonePermissionGranted: StateFlow<Boolean>,
    notificationsGranted: StateFlow<Boolean>,
    batteryOptimizationExempt: StateFlow<Boolean>,
    storageLowMidRec: StateFlow<Boolean>,           // ← NEW (R2 T5)
) : ViewModel() {

    val activeWarning: StateFlow<WarningId?> =
        aggregate(
            cameraPermissionGranted, exactAlarmGranted, storageInsufficient,
            thermal, power, camera,
            microphonePermissionGranted, notificationsGranted, batteryOptimizationExempt,
            storageLowMidRec,                                    // ← NEW
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    companion object {
        /**
         * Combine the ten source flows => highest-priority active
         * [WarningId] via [WarningPrecedence.resolve]. WarningCenterContract
         * NO-GO #6: a throw inside the combine logs and degrades to `null`
         * — a failure to compute a banner must not itself become a banner.
         *
         * kotlinx-coroutines has typed `combine` overloads only up to five
         * flows, so six of the plain booleans are folded into one upstream
         * `combine(vararg flows: Flow<Boolean>) -> Bools6` first (using the
         * reified vararg overload), then a 5-arg
         * `combine(bools6, batteryOptExempt, thermal, power, camera)` does
         * the real work.
         */
        fun aggregate(
            cameraPermissionGranted: Flow<Boolean>,
            exactAlarmGranted: Flow<Boolean>,
            storageInsufficient: Flow<Boolean>,
            thermal: Flow<ThermalStatus>,
            power: Flow<PowerState>,
            camera: Flow<CameraSignalState>,
            microphonePermissionGranted: Flow<Boolean>,
            notificationsGranted: Flow<Boolean>,
            batteryOptimizationExempt: Flow<Boolean>,
            storageLowMidRec: Flow<Boolean>,                // ← NEW (last param)
        ): Flow<WarningId?> {
            val bools6: Flow<Bools6> = combine(
                cameraPermissionGranted,
                exactAlarmGranted,
                storageInsufficient,
                microphonePermissionGranted,
                notificationsGranted,
                storageLowMidRec,
            ) { arr: Array<Boolean> ->
                Bools6(arr[0], arr[1], arr[2], arr[3], arr[4], arr[5])
            }
            return combine(bools6, batteryOptimizationExempt, thermal, power, camera) { b, bo, th, pw, cm ->
                runCatching {
                    WarningPrecedence.resolve(
                        cameraPermissionGranted = b.cameraPermissionGranted,
                        exactAlarmGranted = b.exactAlarmGranted,
                        storageInsufficient = b.storageInsufficient,
                        thermal = th,
                        power = pw,
                        camera = cm,
                        microphonePermissionGranted = b.microphonePermissionGranted,
                        notificationsGranted = b.notificationsGranted,
                        batteryOptimizationExempt = bo,
                        storageLowMidRec = b.storageLowMidRec,
                    )
                }.getOrElse { e ->
                    Log.w("WarningCenter", "warning resolution failed", e)
                    null
                }
            }
        }
    }
}

/** R2 T5 — packs 6 boolean signals so the aggregator stays within `combine`'s 5-flow typed overloads. */
private class Bools6(
    val cameraPermissionGranted: Boolean,
    val exactAlarmGranted: Boolean,
    val storageInsufficient: Boolean,
    val microphonePermissionGranted: Boolean,
    val notificationsGranted: Boolean,
    val storageLowMidRec: Boolean,                  // ← NEW
)
