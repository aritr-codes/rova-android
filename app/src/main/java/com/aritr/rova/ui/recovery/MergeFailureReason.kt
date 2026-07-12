package com.aritr.rova.ui.recovery

import androidx.annotation.StringRes
import com.aritr.rova.R
import com.aritr.rova.service.export.ExportResult

/**
 * Trust System V1 — M9 — the owner-locked, **closed** merge-failure reason set (Q6),
 * transcribed from the frozen `docs/design/warnings-recovery.html` §08
 * ("Merge failure — the closed reason set", DESIGN FROZEN 2026-07-09).
 *
 * A raw Java exception message is **never** shown to a user, in any locale (§08 subtitle;
 * the exception still goes to logs). Every user-visible merge failure resolves to exactly
 * one of these four localized causes; each carries its own `@StringRes` and every one is
 * followed by the same standing reassurance "Your clips are safe." (rendered by the card).
 *
 * **The set is closed (frozen `.noderive` invariant, :725):** a new cause needs a new
 * `@StringRes` **and** a mapping entry here — never a pass-through. [classify] is therefore
 * `when`-exhaustive over the sealed [ExportResult] with no `else`: a future `ExportResult`
 * variant fails to compile until it is deliberately bucketed, which is the closure guarantee.
 *
 * **Classification is from the TYPED cause, not the message string** (parity plan M9 §262).
 * The typed [ExportResult] is only available *before* [com.aritr.rova.service.recovery.RecoveryMerger]
 * collapses every failure into `RecoveryMergeOutcome.MuxFailed(cause: Throwable)`, so the merger
 * classifies at that seam and carries the [MergeFailureReason] forward on the outcome. No message
 * text is ever inspected — the mapping is a total function of the variant.
 *
 * [UNKNOWN] is the fallback (the frozen "Couldn't finish creating the recording.").
 */
enum class MergeFailureReason(@StringRes val messageRes: Int) {
    /** Storage ran out / the destination became unavailable. Frozen: "Storage became unavailable." */
    STORAGE(R.string.recovery_fail_reason_storage),

    /** A source recording file could not be read/copied. Frozen: "One of the recording files couldn't be read." */
    UNREADABLE(R.string.recovery_fail_reason_unreadable),

    /** The combine/finalize/write step failed. Frozen: "The recording couldn't be completed." */
    INCOMPLETE(R.string.recovery_fail_reason_incomplete),

    /** Anything else / unclassified. Frozen: "Couldn't finish creating the recording." */
    UNKNOWN(R.string.recovery_fail_reason_unknown);

    companion object {
        /**
         * Total, message-free classifier over the typed [ExportResult] chain.
         *
         *  - [ExportResult.InsufficientStorage] / [ExportResult.SafFolderUnavailable] → [STORAGE]
         *    (the destination ran out of / lost access to storage). NOTE: `InsufficientStorage`
         *    does not actually reach the failbox — the merger routes it to the `CANT_MERGE` sheet
         *    instead (unchanged) — it is bucketed here only for totality.
         *  - [ExportResult.CopyFailed] → [UNREADABLE] (copying a source segment threw — a recording
         *    file could not be read).
         *  - [ExportResult.MuxFailed] / [ExportResult.RenameFailed] / [ExportResult.PendingInsertFailed] /
         *    [ExportResult.FinalizeFailed] / [ExportResult.ManifestWriteFailed] → [INCOMPLETE]
         *    (the combine/finalize/write step failed).
         *  - [ExportResult.UnknownSession] and the (never-a-failure) [ExportResult.Success] → [UNKNOWN].
         */
        fun classify(result: ExportResult): MergeFailureReason = when (result) {
            is ExportResult.InsufficientStorage -> STORAGE
            is ExportResult.SafFolderUnavailable -> STORAGE
            is ExportResult.CopyFailed -> UNREADABLE
            is ExportResult.MuxFailed -> INCOMPLETE
            is ExportResult.RenameFailed -> INCOMPLETE
            is ExportResult.PendingInsertFailed -> INCOMPLETE
            is ExportResult.FinalizeFailed -> INCOMPLETE
            is ExportResult.ManifestWriteFailed -> INCOMPLETE
            is ExportResult.UnknownSession -> UNKNOWN
            is ExportResult.Success -> UNKNOWN
        }
    }
}
