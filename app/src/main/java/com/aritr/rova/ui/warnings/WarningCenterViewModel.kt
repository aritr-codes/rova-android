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
 * Phase 4.1 — the unified WarningCenter aggregator. Consumes the wired
 * leaf signals and exposes the single highest-priority active warning as
 * a [StateFlow]. ONE ViewModel — no per-category split (WarningCenterContract
 * NO-GO #8). It does NOT own the signals; it observes them.
 *
 * The aggregation logic lives in the [Companion.aggregate] flow operator
 * so it is unit-testable with fake [kotlinx.coroutines.flow.MutableStateFlow]s
 * and no [viewModelScope] — the same posture as PlayerUriResolver /
 * SegmentedTimelineMath / WakeLockPolicy: the testable core is a plain
 * function, the ViewModel is a `stateIn` shell.
 */
class WarningCenterViewModel(
    exactAlarmGranted: StateFlow<Boolean>,
    thermal: StateFlow<ThermalStatus>,
    power: StateFlow<PowerState>,
    camera: StateFlow<CameraSignalState>,
    notificationsGranted: StateFlow<Boolean>,
    batteryOptimizationExempt: StateFlow<Boolean>
) : ViewModel() {

    val activeWarning: StateFlow<WarningId?> =
        aggregate(exactAlarmGranted, thermal, power, camera, notificationsGranted, batteryOptimizationExempt)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    companion object {
        /**
         * Combine the six source flows → highest-priority active [WarningId]
         * via [WarningPrecedence.resolve]. WarningCenterContract NO-GO #6:
         * a throw inside the combine logs and degrades to `null` — a
         * failure to compute a banner must not itself become a banner.
         *
         * kotlinx-coroutines has typed `combine` overloads only up to five
         * flows, so the three plain booleans are folded into one upstream
         * `combine(...) -> Triple` first, then the 4-arg `combine` does the
         * real work. (Phase 4.1b adds three more boolean sources; it will
         * regroup this — `combine(*flows)` over the boolean array, plus the
         * non-boolean ones — additively.)
         */
        fun aggregate(
            exactAlarmGranted: Flow<Boolean>,
            thermal: Flow<ThermalStatus>,
            power: Flow<PowerState>,
            camera: Flow<CameraSignalState>,
            notificationsGranted: Flow<Boolean>,
            batteryOptimizationExempt: Flow<Boolean>
        ): Flow<WarningId?> {
            val booleans: Flow<Triple<Boolean, Boolean, Boolean>> =
                combine(exactAlarmGranted, notificationsGranted, batteryOptimizationExempt) { ea, nt, bo ->
                    Triple(ea, nt, bo)
                }
            return combine(booleans, thermal, power, camera) { (ea, nt, bo), th, pw, cam ->
                runCatching {
                    WarningPrecedence.resolve(
                        exactAlarmGranted = ea,
                        thermal = th,
                        power = pw,
                        camera = cam,
                        notificationsGranted = nt,
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
