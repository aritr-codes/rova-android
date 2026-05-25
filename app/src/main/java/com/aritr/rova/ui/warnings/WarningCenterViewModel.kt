package com.aritr.rova.ui.warnings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aritr.rova.ui.signals.CameraSignalState
import com.aritr.rova.ui.signals.PowerState
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal
import com.aritr.rova.ui.signals.ThermalStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

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
    autoStopEcho: StateFlow<TerminalEcho?> = MutableStateFlow<TerminalEcho?>(null).asStateFlow(), // ← NEW (Phase 4 Slice 2 — 11th source; default keeps pre-T6 call sites green)
    recoveryMergeOutcomeSignal: StateFlow<RecoveryMergeOutcomeSignal.State> =                    // ← NEW (Phase 4.3 — 12th source)
        MutableStateFlow<RecoveryMergeOutcomeSignal.State>(RecoveryMergeOutcomeSignal.State.Idle).asStateFlow(),
    // v3 — injectable scope so plain-JVM unit tests can pass
    // `Dispatchers.Unconfined`-backed CoroutineScope and avoid the
    // `Dispatchers.Main` requirement of `viewModelScope`. Production
    // call-sites construct the VM via `viewModel(factory = ...)` and
    // omit this argument so `viewModelScope` is used as before.
    private val scope: CoroutineScope? = null,
    // Phase 4.1c — initial snooze set + on-mutation callback. Defaults
    // preserve every pre-4.1c call site (in-memory only behaviour).
    // The factory in WarningCenter.kt supplies real values that
    // round-trip through RovaSettings.snoozedWarningIds.
    initialSnoozedIds: Set<WarningId> = emptySet(),
    private val onSnoozeChanged: ((Set<WarningId>) -> Unit)? = null,
    private val onAutoStopDismissed: ((String) -> Unit)? = null,  // ← NEW (Phase 4 Slice 2 callback)
) : ViewModel() {

    private val activeScope: CoroutineScope get() = scope ?: viewModelScope

    // ── v3 — "Why this matters" expand toggle (in-memory; survives only while VM is in scope) ──
    private val _expandedWhy = MutableStateFlow<Set<WarningId>>(emptySet())
    val expandedWhy: StateFlow<Set<WarningId>> = _expandedWhy.asStateFlow()

    fun toggleExpandWhy(id: WarningId) {
        _expandedWhy.update { if (id in it) it - id else it + id }
    }

    // ── v3 + 4.1c — "Don't show again" snooze (persisted via factory callback) ──
    private val _snoozedForever = MutableStateFlow(initialSnoozedIds)
    val snoozedForever: StateFlow<Set<WarningId>> = _snoozedForever.asStateFlow()

    fun snoozeForever(id: WarningId) {
        _snoozedForever.update { it + id }
        onSnoozeChanged?.invoke(_snoozedForever.value)
    }

    /**
     * Phase 4.1c — clear the entire snooze set. Early-returns when already
     * empty so the Settings reset row is idempotent (no redundant disk
     * write when the user taps reset on an already-empty set).
     */
    fun clearSnoozes() {
        if (_snoozedForever.value.isEmpty()) return
        _snoozedForever.value = emptySet()
        onSnoozeChanged?.invoke(emptySet())
    }

    /**
     * Phase 4 Slice 2 — invoked from the WarningCenter Idle-branch overflow
     * router when the user taps "Don't show again" on the auto-stop echo
     * banner. Routes to the factory-wired callback which persists the
     * session id to `RovaSettings.dismissedAutoStopEchoIds` AND calls
     * `app.autoStopEchoSignal.markDismissed(sessionId)` to clear the
     * banner immediately.
     */
    fun dismissAutoStopEcho(sessionId: String) {
        onAutoStopDismissed?.invoke(sessionId)
    }

    /**
     * Phase 4.3 — the session id that is pending a CANT_MERGE dismissal.
     * Non-null only when the signal state is [RecoveryMergeOutcomeSignal.State.Outcome]
     * with outcome [RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.InsufficientStorage].
     * All other signal states (Idle, InProgress, other Outcome subtypes) yield null.
     */
    val pendingCantMergeSessionId: StateFlow<String?> = recoveryMergeOutcomeSignal
        .map { state ->
            when (state) {
                is RecoveryMergeOutcomeSignal.State.Outcome ->
                    (state.outcome as? RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.InsufficientStorage)
                        ?.let { state.sessionId }
                else -> null
            }
        }
        .stateIn(activeScope, SharingStarted.Eagerly, null)

    /** Phase 4.3 — derive a Boolean active flag for the precedence resolver. */
    private val _cantMergeActive: StateFlow<Boolean> = recoveryMergeOutcomeSignal
        .map { state ->
            state is RecoveryMergeOutcomeSignal.State.Outcome &&
                state.outcome is RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.InsufficientStorage
        }
        .stateIn(activeScope, SharingStarted.Eagerly, false)

    private val _resolvedWarning: StateFlow<WarningId?> =
        aggregate(
            cameraPermissionGranted, exactAlarmGranted, storageInsufficient,
            thermal, power, camera,
            microphonePermissionGranted, notificationsGranted, batteryOptimizationExempt,
            storageLowMidRec, autoStopEcho,                      // ← Phase 4 Slice 2
            _cantMergeActive,                                    // ← NEW (Phase 4.3)
        ).stateIn(activeScope, SharingStarted.WhileSubscribed(5_000L), null)

    /**
     * v3 — public sheet/banner render-path signal. Filters [_resolvedWarning]
     * by [_snoozedForever] so a "Don't show again" snooze hides the surface.
     * The Start-gate in `RecordScreen.kt` does NOT collect this — it reads
     * leaf signals (cameraPermissionSignal / storageSignal) directly — so
     * snoozing CAMERA_PERMISSION_DENIED does NOT open Start. Invariant
     * preserved across A7.
     */
    val activeWarning: StateFlow<WarningId?> = combine(
        _resolvedWarning,
        _snoozedForever,
    ) { resolved, snoozed -> resolved?.takeIf { it !in snoozed } }
        .stateIn(activeScope, SharingStarted.Eagerly, null)

    /**
     * Per-session dismiss state — the set of [WarningId]s the user has
     * collapsed to a chip via "Not now" / the sheet's primary action.
     *
     * Lives on the ViewModel (not in composable `rememberSaveable`) on
     * purpose: a dismiss is application state, not UI state. The
     * `WarningCenter` composable is mounted at two sites in `RecordScreen`
     * and early-`return`s before its `remember` slots whenever
     * `activeWarning` blips null — so composable-local dismiss state was
     * discarded on nearly every UI change and the sheet re-presented
     * endlessly (the 2026-05-20 "keeps asking" bug). The ViewModel is one
     * shared instance and survives recomposition, the early-return slot
     * discard, the idle↔active remount, and navigation within the host.
     *
     * Scope is the ViewModel lifetime → a fresh cold app launch re-asks
     * once (intentional: a recording-critical permission warrants one
     * re-check per launch — that is not nagging).
     */
    private val _dismissedWarnings = MutableStateFlow<Set<WarningId>>(emptySet())
    val dismissedWarnings: StateFlow<Set<WarningId>> = _dismissedWarnings.asStateFlow()

    /** Collapse [id] to a chip — called from the sheet's "Not now" / primary action. */
    fun dismiss(id: WarningId) {
        _dismissedWarnings.value = _dismissedWarnings.value + id
    }

    /** Re-expand [id] to its sheet — called when the user taps the collapsed chip. */
    fun restore(id: WarningId) {
        _dismissedWarnings.value = _dismissedWarnings.value - id
    }

    companion object {
        /**
         * Combine the twelve source flows => highest-priority active
         * [WarningId] via [WarningPrecedence.resolve]. WarningCenterContract
         * NO-GO #6: a throw inside the combine logic logs and degrades to
         * `null` — a failure to compute a banner must not itself become a
         * banner.
         *
         * kotlinx-coroutines has typed `combine` overloads only up to five
         * flows. The six plain booleans are folded into a single upstream
         * `Bools6` combine (vararg). The four non-boolean flows
         * (thermal, power, camera, autoStopEcho — Phase 4 Slice 2 added
         * the last) are folded into a single `NonBools4` combine. Outer
         * 4-arg combine then resolves (Phase 4.3 adds `cantMergeActive`
         * as the 4th arg, keeping the outer arity within the typed overloads).
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
            storageLowMidRec: Flow<Boolean>,
            autoStopEcho: Flow<TerminalEcho?>,              // ← Phase 4 Slice 2
            cantMergeActive: Flow<Boolean>,                 // ← NEW (Phase 4.3)
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
            val nonBools4: Flow<NonBools4> = combine(
                thermal, power, camera, autoStopEcho,
            ) { th, pw, cm, ae ->
                NonBools4(th, pw, cm, ae)
            }
            return combine(bools6, batteryOptimizationExempt, nonBools4, cantMergeActive) { b, bo, n4, cm ->
                runCatching {
                    WarningPrecedence.resolve(
                        cameraPermissionGranted = b.cameraPermissionGranted,
                        exactAlarmGranted = b.exactAlarmGranted,
                        storageInsufficient = b.storageInsufficient,
                        thermal = n4.thermal,
                        power = n4.power,
                        camera = n4.camera,
                        microphonePermissionGranted = b.microphonePermissionGranted,
                        notificationsGranted = b.notificationsGranted,
                        batteryOptimizationExempt = bo,
                        storageLowMidRec = b.storageLowMidRec,
                        autoStopEcho = n4.autoStopEcho,
                        cantMergeActive = cm,                // ← NEW (Phase 4.3)
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

/** Phase 4 Slice 2 — packs 4 non-Boolean source flows so the outer combine stays at 3 typed args. */
private class NonBools4(
    val thermal: ThermalStatus,
    val power: PowerState,
    val camera: CameraSignalState,
    val autoStopEcho: TerminalEcho?,
)
