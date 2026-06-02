package com.aritr.rova.service.recovery

import com.aritr.rova.service.export.ExportResult

/**
 * Milestone 2 — sealed classifier over [ExportResult]. The retry loop in
 * [RecoveryMerger] dispatches on this class to decide retry-or-terminate
 * for each export attempt. Pure JVM; no Android, no coroutines.
 *
 * Taxonomy approved during 2026-05-26 brainstorm:
 *  - 4 transient (retry per [MergeRetryPolicy]): `MuxFailed`, `CopyFailed`, `RenameFailed`.
 *    `InsufficientStorage` is also transient at the user level but routes to a
 *    separate flow (notification action + storage poll), so it's its own class.
 *  - 4 permanent (no retry, write `MULTI_SEGMENT_KEPT, NONE`):
 *    `PendingInsertFailed`, `FinalizeFailed`, `ManifestWriteFailed`, `UnknownSession`.
 *  - 1 terminal: `Success`.
 *
 * Spec: `docs/superpowers/specs/2026-05-26-merge-reliability-bundle-design.md` §5 #1.
 * ADR: `docs/adr/0018-recovery-merge-retry-classifier-preflight.md`.
 */
internal sealed class MergeFailureClass {
    /** [ExportResult.Success] — exit loop, write `COMPLETED, NONE`. */
    object Terminal : MergeFailureClass()

    /** Mux / Copy / Rename — retry per [MergeRetryPolicy]. */
    data class Transient(val cause: ExportResult) : MergeFailureClass()

    /** [ExportResult.InsufficientStorage] — notification action + storage poll. */
    data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) : MergeFailureClass()

    /** PendingInsertFailed / FinalizeFailed / ManifestWriteFailed / UnknownSession — no retry. */
    data class Permanent(val cause: ExportResult) : MergeFailureClass()
}

internal fun classifyMergeFailure(result: ExportResult): MergeFailureClass = when (result) {
    is ExportResult.Success -> MergeFailureClass.Terminal
    is ExportResult.MuxFailed -> MergeFailureClass.Transient(result)
    is ExportResult.CopyFailed -> MergeFailureClass.Transient(result)
    is ExportResult.RenameFailed -> MergeFailureClass.Transient(result)
    is ExportResult.InsufficientStorage ->
        MergeFailureClass.InsufficientStorage(result.requiredBytes, result.availableBytes)
    is ExportResult.PendingInsertFailed -> MergeFailureClass.Permanent(result)
    is ExportResult.FinalizeFailed -> MergeFailureClass.Permanent(result)
    is ExportResult.ManifestWriteFailed -> MergeFailureClass.Permanent(result)
    is ExportResult.UnknownSession -> MergeFailureClass.Permanent(result)
    // ADR-0024 — SafFolderUnavailable is a PERMANENT, non-retryable export
    // failure surfaced to the user (WarningId.SAVE_FOLDER_UNAVAILABLE);
    // mirrors the other permanent export failures, never silently retried.
    is ExportResult.SafFolderUnavailable -> MergeFailureClass.Permanent(result)
}
