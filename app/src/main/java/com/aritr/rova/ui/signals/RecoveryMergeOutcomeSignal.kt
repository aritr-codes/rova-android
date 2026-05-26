package com.aritr.rova.ui.signals

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 4.3 — push signal for the recovery merge lifecycle. Lives on
 * [com.aritr.rova.RovaApp] as a lazy singleton; producer is
 * [com.aritr.rova.service.recovery.RecoveryMerger] (running inside the
 * FGS), consumers are:
 *  - `RecoveryViewModel`: combines [State.InProgress] into
 *    `RecoveryCardState.mergeInProgress` so the clip-progress strip fills.
 *  - `WarningCenterViewModel`: lifts [State.Outcome] with
 *    [RecoveryMergeOutcome.InsufficientStorage] into `WarningId.CANT_MERGE`
 *    on the next Idle precedence pick.
 *
 * `acknowledge(sessionId)` is called when the consumer has rendered the
 * outcome (e.g., user dismissed the CANT_MERGE sheet, or the merge
 * succeeded and the card disappeared via terminal write). Idempotent
 * and sessionId-scoped — acknowledging a different session does nothing,
 * so a late acknowledgement for a stale outcome cannot wipe a fresher one.
 */
open class RecoveryMergeOutcomeSignal {

    sealed class State {
        object Idle : State()
        data class InProgress(val sessionId: String, val progress: Float) : State()
        data class Outcome(val sessionId: String, val outcome: RecoveryMergeOutcome) : State()
    }

    sealed class RecoveryMergeOutcome {
        object Succeeded : RecoveryMergeOutcome()
        data class InsufficientStorage(
            val requiredBytes: Long,
            val availableBytes: Long,
        ) : RecoveryMergeOutcome()
        data class MuxFailed(val cause: Throwable) : RecoveryMergeOutcome()
        object ServiceBusy : RecoveryMergeOutcome()
        object UnknownSession : RecoveryMergeOutcome()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    open fun emitInProgress(sessionId: String, progress: Float) {
        _state.value = State.InProgress(sessionId, progress.coerceIn(0f, 1f))
    }

    open fun emitOutcome(sessionId: String, outcome: RecoveryMergeOutcome) {
        _state.value = State.Outcome(sessionId, outcome)
    }

    fun acknowledge(sessionId: String) {
        val current = _state.value
        if (current is State.Outcome && current.sessionId == sessionId) {
            _state.value = State.Idle
        }
    }
}
