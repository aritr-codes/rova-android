package com.aritr.rova.ui.recovery

import androidx.lifecycle.ViewModel
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.service.recovery.RecoveryReport
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal
import com.aritr.rova.utils.RovaLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Phase 2 Slice 2.1b — view-model that adapts the
 * [com.aritr.rova.RovaApp.recoveryReport] StateFlow into a
 * [RecoveryUiState] for the History screen surface.
 *
 * Plain `ViewModel` (NOT `AndroidViewModel`) by design. Dependencies are
 * constructor-injected so the type stays JVM-unit-testable without
 * pulling in `kotlinx-coroutines-test` or a `Dispatchers.Main` rule.
 *
 * The internal scope is built from [ioDispatcher] rather than
 * [androidx.lifecycle.viewModelScope] for the same reason —
 * `viewModelScope` defaults to `Dispatchers.Main.immediate`, which
 * requires `Dispatchers.setMain` infrastructure to test. Tests pass
 * [Dispatchers.Unconfined] so the upstream `combine` runs synchronously
 * on the caller thread; production passes [Dispatchers.IO] so the
 * `loadManifest` disk read does not block the UI.
 *
 * Internal beta correction (smoke 2026-05-03): the VM now owns a
 * dismissed-IDs StateFlow that filters the recovery report upstream of
 * the mapper, and exposes [dismiss] to wire the History screen's
 * Discard button to [com.aritr.rova.data.SessionStore.discardSession]
 * AND the in-memory hide-immediately filter. This avoids a stale-card
 * window between disk delete and the next scan emission.
 */
class RecoveryViewModel(
    recoveryReport: StateFlow<RecoveryReport?>,
    loadManifest: (String) -> SessionManifest?,
    private val discardSession: suspend (String) -> Unit = {},
    /** Phase 4.3 — writes Terminated.MULTI_SEGMENT_KEPT for the Keep-as-raw + Save-segments-only flows. */
    private val markKeptRaw: suspend (String) -> Unit = {},
    /** Phase 4.3 — fires RovaRecordingService.startRecoveryMerge(context, sid). NOT suspended. */
    private val startRecoveryMergeFn: (String) -> Unit = {},
    /** Phase 4.3 — push signal from the recovery merge lifecycle. */
    private val mergeOutcome: StateFlow<RecoveryMergeOutcomeSignal.State> =
        MutableStateFlow(RecoveryMergeOutcomeSignal.State.Idle),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val dismissedIds = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<RecoveryUiState> =
        combine(recoveryReport, dismissedIds, mergeOutcome) { report, dismissed, merge ->
            val base = RecoveryViewSource.buildUiState(report, dismissed, loadManifest)
            applyMergeOutcome(base, merge)
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = RecoveryUiState.Empty,
        )

    private fun applyMergeOutcome(
        base: RecoveryUiState,
        merge: RecoveryMergeOutcomeSignal.State,
    ): RecoveryUiState = when (merge) {
        RecoveryMergeOutcomeSignal.State.Idle -> base
        is RecoveryMergeOutcomeSignal.State.InProgress -> base.copy(
            cards = base.cards.map { c ->
                if (c.sessionId == merge.sessionId) c.copy(mergeInProgress = merge.progress) else c
            },
        )
        is RecoveryMergeOutcomeSignal.State.Outcome -> base.copy(
            cards = base.cards.map { c ->
                if (c.sessionId != merge.sessionId) c
                else when (val o = merge.outcome) {
                    is RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.MuxFailed ->
                        c.copy(mergeInProgress = null, mergeFailedReason = o.cause.message ?: "merge failed")
                    is RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.InsufficientStorage ->
                        c.copy(mergeInProgress = null)   // CANT_MERGE sheet handles the user surface
                    RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.Succeeded,
                    RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.ServiceBusy,
                    RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.UnknownSession ->
                        c.copy(mergeInProgress = null, mergeFailedReason = null)
                }
            },
        )
    }

    /**
     * Hide the visible recovery card immediately and delete its
     * on-disk session directory in the background. The dismissed-IDs
     * filter ensures the card is gone from the next state emission
     * even if the disk delete is still in flight; the actual
     * `discardSession` call is best-effort (a failure just means the
     * directory lingers until the next scan classifies it AGAIN as
     * OFFER_DISCARD — which would simply re-surface the card if the
     * user reopens History; the in-memory dismissed set persists for
     * the VM lifetime so this never visibly regresses for the user
     * on the same screen visit).
     */
    fun dismiss(sessionId: String) {
        dismissedIds.value = dismissedIds.value + sessionId
        scope.launch {
            try {
                discardSession(sessionId)
            } catch (t: Throwable) {
                RovaLog.w("RecoveryViewModel.dismiss: discardSession failed for $sessionId", t)
            }
        }
    }

    /**
     * Phase 4.3 — fires the injected [startRecoveryMergeFn] lambda.
     * Non-suspending; the service emits the merge lifecycle back via
     * [mergeOutcome] which is already combined into [uiState].
     */
    fun merge(sessionId: String) {
        startRecoveryMergeFn(sessionId)
    }

    /**
     * Phase 4.3 — writes [com.aritr.rova.data.Terminated.MULTI_SEGMENT_KEPT]
     * via the injected [markKeptRaw] lambda. Best-effort: a failure logs
     * but does not crash the VM.
     */
    fun keepRaw(sessionId: String) {
        scope.launch {
            try {
                markKeptRaw(sessionId)
            } catch (t: Throwable) {
                RovaLog.w("RecoveryViewModel.keepRaw: failed for $sessionId", t)
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
    }
}
