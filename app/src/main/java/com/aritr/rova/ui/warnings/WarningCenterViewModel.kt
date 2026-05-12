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
 * Phase 4.1 / 4.1b — the unified WarningCenter aggregator. Consumes the
 * nine wired leaf signals and exposes the single highest-priority active
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
    batteryOptimizationExempt: StateFlow<Boolean>
) : ViewModel() {

    val activeWarning: StateFlow<WarningId?> =
        aggregate(
            cameraPermissionGranted, exactAlarmGranted, storageInsufficient,
            thermal, power, camera,
            microphonePermissionGranted, notificationsGranted, batteryOptimizationExempt
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    companion object {
        /**
         * Combine the nine source flows => highest-priority active
         * [WarningId] via [WarningPrecedence.resolve]. WarningCenterContract
         * NO-GO #6: a throw inside the combine logs and degrades to `null`
         * — a failure to compute a banner must not itself become a banner.
         *
         * kotlinx-coroutines has typed `combine` overloads only up to five
         * flows, so five of the six plain booleans are folded into one
         * upstream `combine(...) -> Bools5` first, then a 5-arg
         * `combine(bools5, sixthBoolean, thermal, power, camera)` does the
         * real work.
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
            batteryOptimizationExempt: Flow<Boolean>
        ): Flow<WarningId?> {
            val bools5: Flow<Bools5> = combine(
                cameraPermissionGranted,
                exactAlarmGranted,
                storageInsufficient,
                microphonePermissionGranted,
                notificationsGranted
            ) { cam, ea, st, mic, nt -> Bools5(cam, ea, st, mic, nt) }
            return combine(bools5, batteryOptimizationExempt, thermal, power, camera) { b, bo, th, pw, cm ->
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
                        batteryOptimizationExempt = bo
                    )
                }.getOrElse { e ->
                    Log.w("WarningCenter", "warning resolution failed", e)
                    null
                }
            }
        }
    }
}

/** Phase 4.1b — packs 5 of the 6 boolean signals so the aggregator stays within `combine`'s 5-flow typed overloads. */
private class Bools5(
    val cameraPermissionGranted: Boolean,
    val exactAlarmGranted: Boolean,
    val storageInsufficient: Boolean,
    val microphonePermissionGranted: Boolean,
    val notificationsGranted: Boolean
)
