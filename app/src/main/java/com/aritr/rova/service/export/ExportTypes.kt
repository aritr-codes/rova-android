package com.aritr.rova.service.export

/**
 * Phase 1.7 commit-2 (ADR 0003 §"Recovery routing" partner). Result of a
 * tier-specific exporter's `export(...)` call. Caller (Phase 1.7 commit-7
 * `RovaRecordingService.performMerge` replacement) consumes this to decide
 * the terminal write (`COMPLETED` on `Success`, no terminal change on
 * mux/copy/rename failure — caller writes `USER_STOPPED, MERGE_FAILED`
 * itself).
 *
 * Sealed across all tiers (Tier 1 / 2 / 3) so the caller handles a single
 * result type regardless of tier dispatch.
 */
sealed class ExportResult {

    /**
     * Public artifact at `publicTargetPath` is committed. The two flags
     * disambiguate the "fully retired" outcome from the "scan-pending /
     * private-temp-retained" outcomes that Phase 1.7 cold-launch recovery
     * picks up on the next launch.
     *
     * - `mediaScanCompleted = true && privateTempRetained = false` —
     *   happy path: scan callback fired, private temp file deleted.
     * - `mediaScanCompleted = false && privateTempRetained = true` —
     *   degraded happy path: artifact is on disk and accessible to the
     *   user but the gallery scan didn't index in time, OR the
     *   private temp file delete failed. Recovery's deferred-scan
     *   branch will reconcile on next cold launch.
     *
     * The `mediaScanCompleted = true && privateTempRetained = true`
     * combination cannot be produced by the exporter — it would be a
     * lie about manifest state and complicate recovery routing.
     * `setMediaScanCompleted` is only written after the private temp
     * file delete succeeds.
     */
    data class Success(
        val mediaScanCompleted: Boolean,
        val privateTempRetained: Boolean
    ) : ExportResult()

    /** Mux operation threw. Manifest is `FAILED`; pointers cleared. */
    data class MuxFailed(val cause: Throwable) : ExportResult()

    /** Copy from private temp to `<name>.mp4.part` threw. Manifest `FAILED`; pointers cleared. */
    data class CopyFailed(val cause: Throwable) : ExportResult()

    /** `File.renameTo` returned `false`. Manifest `FAILED`; pointers cleared. */
    object RenameFailed : ExportResult()

    /**
     * Phase 1.7 commit-4 — Tier 1 only. `ContentResolver.insert` for the
     * pending `MediaStore` row returned `null` ([cause] is `null`) or
     * threw ([cause] is the throwable). Manifest pointers are cleared
     * via `setExportFailed`. No row landed on disk; nothing to clean
     * outside the manifest.
     */
    data class PendingInsertFailed(val cause: Throwable?) : ExportResult()

    /**
     * Phase 1.7 commit-4 — Tier 1 only. `ContentResolver.update`
     * flipping `IS_PENDING=0` returned 0 rows ([cause] is `null`) or
     * threw ([cause] is the throwable). The pending row is cleaned up
     * (`ContentResolver.delete`) and the manifest is set to `FAILED`.
     * Tier 1 has no `<name>.mp4.part` analog — finalize is the publish
     * atom, so a finalize-failure leaves nothing user-visible.
     */
    data class FinalizeFailed(val cause: Throwable?) : ExportResult()

    /**
     * A manifest mutation hit `IOException` retry exhaustion or a
     * non-retryable Throwable. Caller defers to the next cold-launch
     * recovery pass — the on-disk manifest may be in an intermediate
     * state.
     */
    data class ManifestWriteFailed(val phase: String, val cause: Throwable) : ExportResult()

    /**
     * Manifest disappeared mid-export (recovery cleanup races, manual
     * file deletion, etc.). The export pipeline aborts; the caller
     * should not write a terminal value.
     */
    data class UnknownSession(val sessionId: String) : ExportResult()
}

/**
 * Phase 1.7 commit-2 (ADR 0003 §"Recovery routing" partner). Result of a
 * tier-specific recovery routine for a single session. Distinct from
 * [ExportResult] because recovery has its own outcome shape — most
 * notably the [Abandoned] case (Tier 3 Case D: nothing on disk to
 * recover; manifest written to `FAILED`).
 *
 * Caller (Phase 1.7 commit-7 `RovaApp.runExportRecovery`) consumes per-
 * session results to decide whether the cleanup pass may proceed.
 */
sealed class RecoveryResult {

    /**
     * Recovery resumed publish-and-finalize and produced an export
     * outcome. The wrapped [ExportResult] carries the same
     * [ExportResult.Success] / failure semantics as live export().
     */
    data class Resumed(val export: ExportResult) : RecoveryResult()

    /**
     * Recovery determined the session is unrecoverable (Tier 3 Case D:
     * no `privateTempPath`, no `<name>.mp4.part`, no `publicTargetPath`).
     * `setExportFailed` was written; manifest is now in `ExportState.FAILED`.
     */
    object Abandoned : RecoveryResult()

    /**
     * Manifest disappeared mid-recovery. Cleanup pass treats this as
     * "session already gone" and skips.
     */
    data class UnknownSession(val sessionId: String) : RecoveryResult()

    /**
     * A manifest mutation during recovery hit retry exhaustion. Caller
     * leaves the session in place and re-runs recovery on the next
     * cold launch.
     */
    data class ManifestWriteFailed(val cause: Throwable) : RecoveryResult()

    /**
     * Phase 1.7 commit-4 (NO-GO patch) — a non-manifest seam threw or
     * returned an ambiguous result during recovery, AFTER the artifact
     * was already proven valid by a prior `validatePending` probe. The
     * row is RETAINED on disk (deleting could destroy a previously-
     * published artifact whose manifest write was lost in a crash).
     * Caller leaves the session in place and re-runs recovery on the
     * next cold launch.
     *
     * [phase] names the seam (`"finalizePendingRow"`); [cause] is the
     * caught throwable. Distinct from [ManifestWriteFailed] because the
     * underlying failure is a `MediaStore`/`ContentResolver` op, not
     * the on-disk manifest write — keeping them separate so failure
     * triage knows where to look.
     */
    data class RetryableFailure(val phase: String, val cause: Throwable) : RecoveryResult()
}

/**
 * Phase 1.7 commit-4 (NO-GO patch) — Tier 1's `IS_PENDING=0` finalize
 * step result. Replaces the prior `Boolean` seam shape because the
 * boolean conflated two semantically different outcomes:
 *
 * - `update(uri, IS_PENDING=0)` returning 1+ rows = "the transition
 *   1→0 just landed on disk".
 * - `update(...)` returning 0 rows = AMBIGUOUS. Could mean the row was
 *   removed under us (live should fail), or could mean the row was
 *   ALREADY at `IS_PENDING=0` (recovery should treat as already-
 *   finalized, NOT delete).
 *
 * Splitting [Finalized] from [NoRowsUpdated] lets live and recovery
 * dispatch differently:
 *
 * | Variant         | Live `export()`                       | Recovery `recover()` (after validatePending=true) |
 * |-----------------|---------------------------------------|---------------------------------------------------|
 * | [Finalized]     | proceed → `setExportFinalized`        | proceed → `setExportFinalized` (Resumed)          |
 * | [NoRowsUpdated] | cleanupAndMap → `FinalizeFailed`      | already-finalized; write FINALIZED, NO delete     |
 * | [Failed]        | cleanupAndMap → `FinalizeFailed(cause)` | `RetryableFailure("finalizePendingRow", cause)`, NO delete |
 *
 * Recovery's "no delete after validate=true" rule is the load-bearing
 * invariant: a process kill or manifest write failure between a
 * successful `update(IS_PENDING=0)` and `setExportFinalized` would
 * leave the artifact published in the gallery with a stale manifest;
 * the next cold-launch's `validatePending` will see it intact. If
 * recovery deleted the row in that path, the user's video would
 * vanish.
 */
sealed class Tier1FinalizeResult {

    /** `update(uri, IS_PENDING=0)` reported 1+ rows updated. */
    object Finalized : Tier1FinalizeResult()

    /**
     * `update(...)` reported 0 rows. Ambiguous — the seam cannot
     * distinguish "row missing" from "row already at IS_PENDING=0";
     * the exporter disambiguates by whether `validatePending` already
     * succeeded for this URI.
     */
    object NoRowsUpdated : Tier1FinalizeResult()

    /** `update(...)` threw. */
    data class Failed(val cause: Throwable) : Tier1FinalizeResult()
}
