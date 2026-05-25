package com.aritr.rova.service.recovery

import com.aritr.rova.service.export.ExportResult
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal.RecoveryMergeOutcome
import java.io.File

/**
 * Phase 4.3 — wraps the `ExportPipeline.exportRecovered` call for a
 * single recovery merge session. Owns:
 *  - Segment list lookup (via [loadSegments]) from `SessionStore`.
 *  - Session-directory resolution (via [sessionDirOf]).
 *  - Translation from `ExportResult` to `RecoveryMergeOutcome`.
 *  - Push to [RecoveryMergeOutcomeSignal] for both progress + final.
 *
 * Constructor seams keep this JVM-testable: production wires
 * `ExportPipeline.exportRecovered` + `app.sessionStore` lambdas;
 * tests inject pure-function fakes. `onProgress` is split out so tests
 * can capture progress without subclassing the final `RecoveryMergeOutcomeSignal`.
 *
 * Hosted by `RovaRecordingService.handleRecoveryMergeStart` (Task 8).
 */
class RecoveryMerger(
    private val loadSegments: (sessionId: String) -> List<File>,
    private val sessionDirOf: (sessionId: String) -> File,
    private val exportRecovered: suspend (sessionDir: File, segments: List<File>, onProgress: (Float) -> Unit) -> ExportResult,
    private val signal: RecoveryMergeOutcomeSignal,
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
        val result = exportRecovered(sessionDir, segments) { progress ->
            onProgress(sessionId, progress)
        }
        val outcome = result.toRecoveryOutcome()
        signal.emitOutcome(sessionId, outcome)
        return outcome
    }

    private fun ExportResult.toRecoveryOutcome(): RecoveryMergeOutcome = when (this) {
        is ExportResult.Success -> RecoveryMergeOutcome.Succeeded
        is ExportResult.InsufficientStorage ->
            RecoveryMergeOutcome.InsufficientStorage(requiredBytes, availableBytes)
        is ExportResult.MuxFailed -> RecoveryMergeOutcome.MuxFailed(cause)
        is ExportResult.UnknownSession -> RecoveryMergeOutcome.UnknownSession
        // All remaining ExportResult variants collapse to MuxFailed for the
        // user-facing recovery surface — the UI cannot distinguish them and
        // the user action is identical (retry or keep raw). The underlying
        // ExportResult is still recorded by the pipeline's failure path;
        // only the user-surface compresses.
        is ExportResult.CopyFailed -> RecoveryMergeOutcome.MuxFailed(cause)
        is ExportResult.RenameFailed -> RecoveryMergeOutcome.MuxFailed(IllegalStateException("rename failed"))
        is ExportResult.PendingInsertFailed -> RecoveryMergeOutcome.MuxFailed(cause ?: IllegalStateException("pending insert failed"))
        is ExportResult.FinalizeFailed -> RecoveryMergeOutcome.MuxFailed(cause ?: IllegalStateException("finalize failed"))
        is ExportResult.ManifestWriteFailed -> RecoveryMergeOutcome.MuxFailed(cause)
    }
}
