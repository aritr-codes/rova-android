package com.aritr.rova.service

import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal

/**
 * Phase 4.3 — pure decision helper for `RovaRecordingService`'s
 * `handleRecoveryMergeStart` branch. Extracted so the routing logic
 * is JVM-testable without a live `Service` instance. The service
 * branch reads its `isRecordingActive` boolean from existing
 * recording-state and dispatches per [RecoveryMergeStartDecision].
 */
internal sealed class RecoveryMergeStartDecision {
    data class Accept(val sessionId: String) : RecoveryMergeStartDecision()
    data class Reject(val outcome: RecoveryMergeOutcomeSignal.RecoveryMergeOutcome) : RecoveryMergeStartDecision()
}

internal fun recoveryMergeStartGate(
    isRecordingActive: Boolean,
    sessionId: String,
): RecoveryMergeStartDecision = when {
    sessionId.isBlank() -> RecoveryMergeStartDecision.Reject(
        RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.UnknownSession,
    )
    isRecordingActive -> RecoveryMergeStartDecision.Reject(
        RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.ServiceBusy,
    )
    else -> RecoveryMergeStartDecision.Accept(sessionId)
}
