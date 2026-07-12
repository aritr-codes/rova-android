package com.aritr.rova.service.recovery

import com.aritr.rova.service.export.ExportResult
import com.aritr.rova.ui.recovery.MergeFailureReason
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal.RecoveryMergeOutcome
import kotlinx.coroutines.delay
import java.io.File

/**
 * Phase 4.3 + Milestone 2 — wraps the `ExportPipeline.exportRecovered` call
 * for a single recovery merge session. Owns:
 *  - Segment list lookup (via [loadSegments]) from `SessionStore`.
 *  - Session-directory resolution (via [sessionDirOf]).
 *  - Milestone 2 retry loop: classifier-driven transient/permanent
 *    dispatch over [ExportResult] with [MergeRetryPolicy] backoff.
 *  - Milestone 2 separate InsufficientStorage flow: storage-poll via
 *    [pollForStorage] callback, 30-second polling cadence, 10-minute cap.
 *  - Translation from terminal [ExportResult] → user-surface
 *    [RecoveryMergeOutcome] (existing Phase 4.3 contract).
 *  - Push to [RecoveryMergeOutcomeSignal] for both progress + final.
 *
 * Constructor seams keep this JVM-testable: production wires
 * `ExportPipeline.exportRecovered` + real polling lambdas; tests inject
 * pure-function fakes plus a `backoffMillisOverride` to fast-forward
 * exponential delays.
 *
 * Hosted by `RovaRecordingService.handleRecoveryMergeStart`.
 *
 * Spec: `docs/superpowers/specs/2026-05-26-merge-reliability-bundle-design.md` §6.
 * ADR: `docs/adr/0018-recovery-merge-retry-classifier-preflight.md`.
 */
class RecoveryMerger(
    private val loadSegments: (sessionId: String) -> List<File>,
    private val sessionDirOf: (sessionId: String) -> File,
    private val exportRecovered: suspend (sessionDir: File, segments: List<File>, onProgress: (Float) -> Unit) -> ExportResult,
    private val signal: RecoveryMergeOutcomeSignal,
    /**
     * Storage-poll callback. Called per 30-second tick during the
     * InsufficientStorage flow. Returns `true` when sufficient space is
     * available (the merge can retry from `attempt = 1`). The merger
     * stops polling after this returns true OR after the 10-minute cap.
     *
     * Production wiring (Service): query
     * `StorageManager.getAllocatableBytes(externalRoot)` and compare
     * against `requiredBytes`.
     */
    private val pollForStorage: suspend (requiredBytes: Long, availableBytesEstimate: Long) -> Boolean = { _, _ -> false },
    /**
     * Override [MergeRetryPolicy.backoffMillisFor] for tests. Production
     * call sites omit this argument so the real exponential schedule
     * applies. Tests pass `{ 0L }` to fast-forward.
     */
    private val backoffMillisOverride: ((attempt: Int) -> Long)? = null,
    /**
     * Poll cap in milliseconds. Production: 10 minutes. Tests can override
     * to bound runtime.
     */
    private val pollCapMillis: Long = 10L * 60L * 1000L,
    /**
     * Poll cadence in milliseconds. Production: 30 seconds. Tests pass 0L.
     */
    private val pollIntervalMillis: Long = 30L * 1000L,
    private val onProgress: (String, Float) -> Unit = signal::emitInProgress,
) {

    suspend fun run(sessionId: String): RecoveryMergeOutcome {
        val segments = loadSegments(sessionId)
        if (segments.isEmpty()) {
            val outcome = RecoveryMergeOutcome.UnknownSession
            signal.emitOutcome(sessionId, outcome)
            return outcome
        }
        val sessionDir = sessionDirOf(sessionId)

        var attempt = 1
        while (true) {
            val result = exportRecovered(sessionDir, segments) { progress ->
                onProgress(sessionId, progress)
            }
            when (val classified = classifyMergeFailure(result)) {
                is MergeFailureClass.Terminal -> {
                    val outcome = RecoveryMergeOutcome.Succeeded
                    signal.emitOutcome(sessionId, outcome)
                    return outcome
                }
                is MergeFailureClass.Transient -> {
                    if (attempt < MergeRetryPolicy.MAX_ATTEMPTS) {
                        val backoff = backoffMillisOverride?.invoke(attempt)
                            ?: MergeRetryPolicy.backoffMillisFor(attempt)
                        delay(backoff)
                        attempt += 1
                        continue
                    }
                    // Exhausted — translate the LAST transient ExportResult to user surface.
                    val outcome = classified.cause.toRecoveryOutcome()
                    signal.emitOutcome(sessionId, outcome)
                    return outcome
                }
                is MergeFailureClass.InsufficientStorage -> {
                    val freed = waitForStorage(classified.requiredBytes, classified.availableBytes)
                    if (freed) {
                        // Reset attempt counter and re-enter the retry loop.
                        attempt = 1
                        continue
                    }
                    val outcome = RecoveryMergeOutcome.InsufficientStorage(
                        classified.requiredBytes, classified.availableBytes
                    )
                    signal.emitOutcome(sessionId, outcome)
                    return outcome
                }
                is MergeFailureClass.Permanent -> {
                    val outcome = classified.cause.toRecoveryOutcome()
                    signal.emitOutcome(sessionId, outcome)
                    return outcome
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable — retry loop always returns")
    }

    private suspend fun waitForStorage(requiredBytes: Long, lastAvailable: Long): Boolean {
        val deadline = pollCapMillis
        var elapsed = 0L
        while (elapsed < deadline) {
            if (pollForStorage(requiredBytes, lastAvailable)) return true
            delay(pollIntervalMillis)
            elapsed += pollIntervalMillis
        }
        return false
    }

    // M9 — the failure branches classify the TYPED ExportResult into the owner-locked
    // MergeFailureReason (frozen §08) HERE, the last seam where the variant is still known,
    // and carry it on MuxFailed.reason. Downstream (RecoveryViewModel → failbox) reads the
    // reason's @StringRes, never `cause.message`. `cause` is retained for logs/diagnostics.
    private fun ExportResult.toRecoveryOutcome(): RecoveryMergeOutcome = when (this) {
        is ExportResult.Success -> RecoveryMergeOutcome.Succeeded
        is ExportResult.InsufficientStorage ->
            RecoveryMergeOutcome.InsufficientStorage(requiredBytes, availableBytes)
        is ExportResult.MuxFailed -> RecoveryMergeOutcome.MuxFailed(cause, MergeFailureReason.classify(this))
        is ExportResult.UnknownSession -> RecoveryMergeOutcome.UnknownSession
        is ExportResult.CopyFailed -> RecoveryMergeOutcome.MuxFailed(cause, MergeFailureReason.classify(this))
        is ExportResult.RenameFailed -> RecoveryMergeOutcome.MuxFailed(IllegalStateException("rename failed"), MergeFailureReason.classify(this))
        is ExportResult.PendingInsertFailed -> RecoveryMergeOutcome.MuxFailed(cause ?: IllegalStateException("pending insert failed"), MergeFailureReason.classify(this))
        is ExportResult.FinalizeFailed -> RecoveryMergeOutcome.MuxFailed(cause ?: IllegalStateException("finalize failed"), MergeFailureReason.classify(this))
        is ExportResult.ManifestWriteFailed -> RecoveryMergeOutcome.MuxFailed(cause, MergeFailureReason.classify(this))
        // ADR-0024 — terminal export failure surfaced to the user; mirrors
        // the other permanent export failures, all of which map to MuxFailed
        // (the outcome's "failed, show me" bucket). cause is nullable.
        is ExportResult.SafFolderUnavailable -> RecoveryMergeOutcome.MuxFailed(cause ?: IllegalStateException("save folder unavailable"), MergeFailureReason.classify(this))
    }
}
