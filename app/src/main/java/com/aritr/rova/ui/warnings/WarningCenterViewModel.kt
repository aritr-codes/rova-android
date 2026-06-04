package com.aritr.rova.ui.warnings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aritr.rova.ui.signals.CameraSignalState
import com.aritr.rova.ui.signals.PowerState
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal
import com.aritr.rova.ui.signals.ThermalStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    saveFolderUnavailable: StateFlow<Boolean> =                                                  // ← NEW (B4b ADR-0024 — 13th source)
        MutableStateFlow(false).asStateFlow(),
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
    // ── Battery-optimization card "once per 24h" rate-limit ──
    // Persisted epoch-millis of the last time the card was shown, read ONCE
    // at construction. The factory in WarningCenter.kt supplies the live
    // value from RovaSettings.batteryOptCardLastShownAt; defaults keep
    // pre-rate-limit call sites (tests, previews) green (never suppress).
    initialBatteryCardLastShownAt: Long = 0L,
    // Injectable time seam (project seam pattern) so the suppress decision is
    // deterministic under JVM unit tests.
    private val now: () -> Long = { System.currentTimeMillis() },
    // One-shot persistence callback: invoked AT MOST once per session, the
    // first time the battery card actually becomes the resolved warning while
    // NOT suppressed. The factory writes RovaSettings.batteryOptCardLastShownAt.
    private val onBatteryCardShown: ((Long) -> Unit)? = null,
) : ViewModel() {

    private val activeScope: CoroutineScope get() = scope ?: viewModelScope

    // Captured ONCE at construction, BEFORE any "shown now" write — otherwise
    // recording the new timestamp would immediately re-suppress and the card
    // would flash and vanish within the same session.
    private val suppressBatteryCardThisSession: Boolean =
        shouldSuppressBatteryCard(initialBatteryCardLastShownAt, now())

    // One-shot guard so the timestamp is persisted exactly once per session.
    private var batteryCardTimestampRecorded = false

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
            saveFolderUnavailable,                               // ← NEW (B4b ADR-0024)
            suppressBatteryCard = suppressBatteryCardThisSession, // ← NEW (once-per-24h rate-limit)
        ).stateIn(activeScope, SharingStarted.WhileSubscribed(5_000L), null)

    /** Phase 4.2 — multi-active stream parallel to [_resolvedWarning]; Eagerly so tests can read .value synchronously. */
    private val _allActive: StateFlow<List<WarningId>> = aggregateAllActive(
        cameraPermissionGranted, exactAlarmGranted, storageInsufficient,
        thermal, power, camera,
        microphonePermissionGranted, notificationsGranted, batteryOptimizationExempt,
        storageLowMidRec, autoStopEcho,
        _cantMergeActive,
        saveFolderUnavailable,                               // ← NEW (B4b ADR-0024)
    ).stateIn(activeScope, SharingStarted.Eagerly, emptyList())

    /**
     * Phase 4.2 — per-session dismissals from the History strip's X button.
     * In-memory only, no persistence. Distinct from [_dismissedWarnings]
     * which collapses sheets-to-chips on the Record screen.
     *
     * Cleared on signal down-edge (Task 5) so the next signal up-edge
     * re-surfaces the card.
     */
    private val _historyStripDismissedThisSession = MutableStateFlow<Set<WarningId>>(emptySet())

    private val _historyActive: StateFlow<List<WarningId>> = combine(
        _allActive, _snoozedForever, _historyStripDismissedThisSession,
    ) { all, snoozed, dismissed ->
        all.filter { it in HISTORY_WARNINGS && it !in snoozed && it !in dismissed }
    }.stateIn(activeScope, SharingStarted.Eagerly, emptyList())

    private val _settingsActive: StateFlow<List<WarningId>> = combine(
        _allActive, _snoozedForever,
    ) { all, snoozed ->
        all.filter { it in SETTINGS_WARNINGS && it !in snoozed }
    }.stateIn(activeScope, SharingStarted.Eagerly, emptyList())

    init {
        // Phase 4.2 — watch _allActive for down-edges (ids that were active
        // last emission and aren't now) and clear matching entries in the
        // History strip dismissed set. Effect: a fresh up-edge re-surfaces
        // the card. Settings has no dismissed set, so this only affects
        // History routing.
        activeScope.launch {
            var previous: Set<WarningId> = emptySet()
            _allActive.collect { current ->
                val nowSet = current.toSet()
                val downEdges = previous - nowSet
                if (downEdges.isNotEmpty()) {
                    _historyStripDismissedThisSession.update { it - downEdges }
                }
                previous = nowSet
            }
        }
    }

    /**
     * Phase 4.2 — per-screen multi-active flow. Returns the ordinal-sorted
     * list of currently-active WarningIds filtered to the screen's allowlist,
     * minus any IDs snoozed forever. Record always returns empty — Record
     * consumes [activeWarning] (single resolved id), the existing hard
     * invariant.
     */
    internal fun activeWarningsFor(screen: WarningScreen): StateFlow<List<WarningId>> = when (screen) {
        WarningScreen.Record -> EmptyActiveList
        WarningScreen.History -> _historyActive
        WarningScreen.Settings -> _settingsActive
    }

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

    init {
        // Battery-card rate-limit — record "shown now" exactly once per session,
        // the first time the battery card is the visible (snooze-filtered)
        // warning while NOT suppressed this session. The suppress decision was
        // already captured at construction, so this write cannot self-suppress
        // the currently-visible card. `activeWarning` is the public flow the UI
        // actually renders; if it ever surfaces BATTERY_OPTIMIZATION_ON the card
        // was shown. This init block sits AFTER `activeWarning`'s declaration so
        // the reference is fully initialized when the collector starts.
        val onShown = onBatteryCardShown
        if (!suppressBatteryCardThisSession && onShown != null) {
            activeScope.launch {
                activeWarning.collect { id ->
                    if (id == WarningId.BATTERY_OPTIMIZATION_ON && !batteryCardTimestampRecorded) {
                        batteryCardTimestampRecorded = true
                        onShown.invoke(now())
                    }
                }
            }
        }
    }

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

    /**
     * Phase 4.2 — user tapped the X on a History strip card. Removes
     * [id] from the History list until either: the signal flips off
     * and back on (down-edge auto-clear, Task 5), or the app restarts.
     * No-op when [id] is not in [HISTORY_WARNINGS] (defensive guard).
     */
    internal fun dismissOnHistoryStrip(id: WarningId) {
        if (id !in HISTORY_WARNINGS) return
        _historyStripDismissedThisSession.update { it + id }
    }

    companion object {
        /** Phase 4.2 — shared empty list flow for [activeWarningsFor]`(Record)` (no per-call allocation). */
        private val EmptyActiveList: StateFlow<List<WarningId>> =
            MutableStateFlow<List<WarningId>>(emptyList()).asStateFlow()

        /**
         * Phase 4.2 — parallel to [aggregate]: emits the full ordinal-sorted
         * active set via [WarningPrecedence.allActive]. Same combine plumbing
         * as [aggregate] (Bools6 + NonBools4 packing); only the terminal
         * mapper differs. Failure inside the combine logs and degrades to
         * `emptyList()` — never a banner-from-failure.
         */
        fun aggregateAllActive(
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
            autoStopEcho: Flow<TerminalEcho?>,
            cantMergeActive: Flow<Boolean>,
            saveFolderUnavailable: Flow<Boolean> = MutableStateFlow(false).asStateFlow(), // ← NEW (B4b ADR-0024)
        ): Flow<List<WarningId>> {
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
            // Outer arity = 5 (typed-combine limit; was 4 before B4b).
            return combine(bools6, batteryOptimizationExempt, nonBools4, cantMergeActive, saveFolderUnavailable) { b, bo, n4, cm, sfu ->
                runCatching {
                    WarningPrecedence.allActive(
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
                        cantMergeActive = cm,
                        saveFolderUnavailable = sfu,                        // ← NEW (B4b ADR-0024)
                    )
                }.getOrElse { e ->
                    Log.w("WarningCenter", "warning resolution failed", e)
                    emptyList()
                }
            }
        }

        /**
         * Combine the thirteen source flows => highest-priority active
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
         * 5-arg combine then resolves (Phase 4.3 adds `cantMergeActive`
         * as the 4th arg; B4b ADR-0024 adds `saveFolderUnavailable` as the
         * 5th arg — now at the typed-overload limit).
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
            saveFolderUnavailable: Flow<Boolean> = MutableStateFlow(false).asStateFlow(), // ← NEW (B4b ADR-0024)
            suppressBatteryCard: Boolean = false,           // ← NEW (once-per-24h rate-limit; constant per session, decided at VM init)
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
            // Outer arity = 5 (typed-combine limit; was 4 before B4b).
            return combine(bools6, batteryOptimizationExempt, nonBools4, cantMergeActive, saveFolderUnavailable) { b, bo, n4, cm, sfu ->
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
                        cantMergeActive = cm,                    // ← NEW (Phase 4.3)
                        saveFolderUnavailable = sfu,             // ← NEW (B4b ADR-0024)
                        suppressBatteryCard = suppressBatteryCard, // ← NEW (once-per-24h rate-limit)
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
