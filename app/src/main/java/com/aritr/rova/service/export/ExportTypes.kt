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
}
