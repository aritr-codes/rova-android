package com.aritr.rova.service.export

import com.aritr.rova.data.ExportMutationResult
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import com.aritr.rova.utils.RovaLog
import java.io.File
import java.io.FileDescriptor

/**
 * Phase 1.7 commit-4 (ADR 0003 §"Recovery routing", Tier 1 column +
 * §"FD Mode Amendment"). API 29+ export pipeline + cold-launch
 * per-session recovery.
 *
 * Tier 1 constraints (per ADR 0003 §"FD Mode Amendment" + §"Forbidden
 * combinations"):
 * - `MediaStore` pending-row insert + `IS_PENDING=0` finalize is the
 *   publish atom. Tier 1 does NOT use `<name>.mp4.part` + `renameTo`
 *   (that is Tier 2/3's path-based atom).
 * - The pending-row FD MUST be opened with `"rw"` mode. `"w"` produces a
 *   non-seekable FD on Tier 1 and breaks `MediaMuxer.stop()`'s moov-atom
 *   rewrite. The `checkPendingFdModeIsRW` lint guards source for the
 *   `"w"` literal; this exporter passes `"rw"` explicitly to the
 *   `withPendingFd` seam.
 * - `MediaMuxer(FileDescriptor)` constructor is reachable at API 26 but
 *   reserved by ADR for Tier 1. Tier 2/3 use `MediaMuxer(String, ...)`
 *   via the shared [PreQExportCore].
 * - No `MediaScannerConnection.scanFile` — `MediaStore` insert auto-
 *   indexes once `IS_PENDING=0` is committed. The bounded
 *   [MediaScanWaiter] is Tier 2/3-only.
 *
 * Live-export sequence ([export]):
 *  1. `insertPendingRow(sessionId)` — `MediaStore.Video.Media` row with
 *     `IS_PENDING=1`. Returned URI is the manifest-pointer source of
 *     truth; insert failures abort BEFORE any manifest write.
 *  2. [SessionStore.setExportPending] — manifest commit point. Writes
 *     `pendingUri` and `exportState=MUXING` in a single atomic write.
 *     MUST happen BEFORE the muxer opens the FD so cold-launch recovery
 *     can always find the row that matches the manifest pointer.
 *  3. `withPendingFd(uri, "rw") { fd -> mux(segments, fd) }` — FD lives
 *     for the lifetime of the block; the seam closes it on exit even on
 *     throw.
 *  4. `finalizePendingRow(uri)` — `IS_PENDING=0`. Tier 1's publish atom.
 *  5. [SessionStore.setExportFinalized] with `clearPrivateTempPath=false`
 *     — Tier 1 never sets `privateTempPath`, so the argument is a no-op
 *     here; `pendingUri` is RETAINED as the artifact reference per ADR
 *     0003 §"Pointer Lifecycle" (final-artifact pointers retained).
 *
 * Recovery sequence ([recover]):
 *  - Manifest-referenced `pendingUri` only. No listing / query sweep —
 *    that is commit 5's orphan sweep.
 *  - `null` pointer → abandon (no row to clean; manifest set FAILED).
 *  - `validatePending(uri)` returns `false` (missing, unopenable, or
 *    `MediaFileValidator` discipline — track + readSampleData — rejects
 *    the byte stream) → delete the row (best-effort), set FAILED,
 *    abandon. This is the ONLY recovery path that deletes a row.
 *  - `validatePending` returns `true` → finalize, then write FINALIZED.
 *    Per the NO-GO patch invariant, recovery MUST NOT delete the row
 *    from this point on; any anomaly is treated conservatively because
 *    the artifact may already be a successfully-published video whose
 *    FINALIZED manifest write was lost in a prior crash. Specifically
 *    (per [Tier1FinalizeResult]):
 *    - `Finalized` → `setExportFinalized`; `Resumed(Success)`.
 *    - `NoRowsUpdated` → row already at `IS_PENDING=0`; trust the
 *      artifact, write FINALIZED, `Resumed(Success)`. NO delete.
 *    - `Failed(cause)` → MediaStore op threw post-validate; row retained;
 *      [RecoveryResult.RetryableFailure] defers to the next cold launch.
 *    `pendingUri` retained on every successful FINALIZED write.
 *
 * Result-bearing cleanup contract (matches Tier 2/3 / [PreQExportCore]):
 * the live path's [cleanupAndMap] and recovery's [abandon] propagate
 * `setExportFailed`'s [ExportMutationResult] — a `Failed` from retry
 * exhaustion surfaces as [ExportResult.ManifestWriteFailed], not a
 * naturalFailure lie. Cold-launch recovery cannot trust a Success-of-
 * Cleanup that didn't actually land.
 *
 * Injectable seams (test hooks; production wiring lives in
 * [Tier1AndroidOps] under `@RequiresApi(Build.VERSION_CODES.Q)`):
 * - [insertPendingRow] — returns the URI string of the new pending row,
 *   or `null` if `ContentResolver.insert` returned `null`. May throw.
 * - [withPendingFd] — opens an FD for [uri] with [mode], runs [block]
 *   against it, closes the FD on exit. The mode parameter is exposed so
 *   tests can record-and-assert `"rw"`; the lint enforces the literal
 *   in source.
 * - [mux] — segment-list-to-MP4 mux against the provided FD. Production
 *   wraps `MediaMuxer(FileDescriptor, MUXER_OUTPUT_MPEG_4)` (commit 7).
 * - [finalizePendingRow] — `ContentResolver.update(uri, IS_PENDING=0)`.
 *   Returns `true` if the update affected rows, `false` if zero rows
 *   matched. May throw.
 * - [deletePendingRow] — best-effort `ContentResolver.delete(uri)` for
 *   cleanup-on-failure paths. Returns `true` if a row was removed.
 * - [validatePending] — recovery probe. Production wraps
 *   `MediaExtractor.setDataSource(fd)` + `trackCount > 0`. Default
 *   returns `true` (test stub) so tests that don't care about probe
 *   behavior don't have to wire it.
 *
 * NOT in scope for commit 4:
 * - Orphan sweep (commit 5). [recover] handles only manifests with a
 *   referenced `pendingUri` — abandoned rows that were inserted before
 *   the manifest commit failed are commit 5's responsibility.
 * - `RovaApp.runExportRecovery` integration (commit 6).
 * - `RovaRecordingService.performMerge` replacement (commit 7).
 * - Terminal `markTerminated(COMPLETED, NONE)` — caller writes the
 *   terminal value based on [ExportResult] per ADR 0006 §"Terminal-
 *   Write Ordering".
 */
class Tier1Exporter(
    private val sessionStore: SessionStore,
    private val insertPendingRow: suspend (sessionId: String) -> String?,
    private val withPendingFd: suspend (
        uri: String,
        mode: String,
        block: suspend (FileDescriptor) -> Unit
    ) -> Unit,
    private val mux: suspend (segments: List<File>, fd: FileDescriptor) -> Unit,
    private val finalizePendingRow: suspend (uri: String) -> Tier1FinalizeResult,
    private val deletePendingRow: suspend (uri: String) -> Boolean,
    private val validatePending: suspend (uri: String) -> Boolean = { true }
) {

    suspend fun export(
        sessionId: String,
        segments: List<File>,
        side: com.aritr.rova.service.dualrecord.VideoSide? = null
    ): ExportResult {
        // 1. Insert pending row. No manifest write yet — failure here
        //    cleans up the manifest only (no row to delete).
        val uri: String = try {
            insertPendingRow(sessionId)
        } catch (t: Throwable) {
            RovaLog.w("$TAG: insertPendingRow threw for $sessionId", t)
            return cleanupAndMap(sessionId, ExportResult.PendingInsertFailed(t), uri = null, side = side)
        } ?: return cleanupAndMap(
            sessionId,
            ExportResult.PendingInsertFailed(null),
            uri = null,
            side = side
        )

        // 2. Manifest commit point — BEFORE muxer opens the FD. Phase 6.1b
        //    T12: when `side != null` the per-side mutator populates the
        //    portrait/landscape `pendingUri` field; the shared `pendingUri`
        //    is left untouched (T13's caller writes the final shared
        //    `exportState` after both sides settle).
        val pendingResult = if (side == null) {
            sessionStore.setExportPending(sessionId, uri)
        } else {
            sessionStore.setExportPendingForSide(sessionId, side, uri)
        }
        when (pendingResult) {
            is ExportMutationResult.Wrote -> {}
            is ExportMutationResult.UnknownSession -> {
                safeDeleteRow(uri)
                return ExportResult.UnknownSession(pendingResult.sessionId)
            }
            is ExportMutationResult.Failed -> {
                safeDeleteRow(uri)
                return ExportResult.ManifestWriteFailed(
                    if (side == null) "setExportPending" else "setExportPendingForSide",
                    pendingResult.cause
                )
            }
        }

        // 3. Open FD ("rw" mandatory per ADR 0003 §FD Mode Amendment) and
        //    mux into it. The seam owns FD close-on-exit even on throw.
        val muxThrew: Throwable? = try {
            withPendingFd(uri, "rw") { fd -> mux(segments, fd) }
            null
        } catch (t: Throwable) {
            t
        }
        if (muxThrew != null) {
            RovaLog.w("$TAG: mux failed for $sessionId", muxThrew)
            return cleanupAndMap(sessionId, ExportResult.MuxFailed(muxThrew), uri = uri, side = side)
        }

        // 4. Tier 1's publish atom — IS_PENDING=0. Live treats every
        //    non-`Finalized` result as failure: we just inserted the row
        //    in step 1 and proved the FD is writable in step 3, so a
        //    `NoRowsUpdated` here means the row went missing under us
        //    (concurrent delete?) — not "already finalized" in the
        //    recovery sense.
        when (val r = finalizePendingRow(uri)) {
            Tier1FinalizeResult.Finalized -> { /* proceed */ }
            Tier1FinalizeResult.NoRowsUpdated -> {
                RovaLog.w("$TAG: finalizePendingRow reported 0 rows updated for $sessionId")
                return cleanupAndMap(
                    sessionId,
                    ExportResult.FinalizeFailed(null),
                    uri = uri,
                    side = side
                )
            }
            is Tier1FinalizeResult.Failed -> {
                RovaLog.w("$TAG: finalizePendingRow threw for $sessionId", r.cause)
                return cleanupAndMap(
                    sessionId,
                    ExportResult.FinalizeFailed(r.cause),
                    uri = uri,
                    side = side
                )
            }
        }

        // 5. FINALIZED. pendingUri retained; clearPrivateTempPath is a
        //    no-op for Tier 1 (never sets privateTempPath in the first
        //    place) but the parameter is required by the shared mutator.
        //    Phase 6.1b T12: when `side != null` route to the per-side
        //    mutator — shared `exportState` is NOT advanced here; T13's
        //    caller writes the final shared `exportState` after both
        //    sides settle. The per-side mutator records `pendingUri` as
        //    the publicTargetPath for Tier 1 (the MediaStore URI is the
        //    artifact reference; there is no separate path).
        val finalizedResult = if (side == null) {
            sessionStore.setExportFinalized(sessionId, clearPrivateTempPath = false)
        } else {
            sessionStore.setExportFinalizedForSide(
                sessionId = sessionId,
                side = side,
                publicTargetPath = uri,
                clearPrivateTempPath = false
            )
        }
        return when (finalizedResult) {
            is ExportMutationResult.Wrote ->
                ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false)
            is ExportMutationResult.UnknownSession ->
                ExportResult.UnknownSession(finalizedResult.sessionId)
            is ExportMutationResult.Failed ->
                ExportResult.ManifestWriteFailed(
                    if (side == null) "setExportFinalized" else "setExportFinalizedForSide",
                    finalizedResult.cause
                )
        }
    }

    suspend fun recover(
        manifest: SessionManifest,
        side: com.aritr.rova.service.dualrecord.VideoSide? = null
    ): RecoveryResult {
        require(manifest.exportTier == ExportTier.TIER1_API29_PLUS) {
            "$TAG.recover called with tier ${manifest.exportTier} (expected TIER1_API29_PLUS)"
        }
        val sessionId = manifest.sessionId
        // Phase 6.1b T14: when `side != null`, read the per-side
        // pendingUri pointer. Single-mode (`side == null`) is
        // byte-identical to pre-T14.
        val uri = when (side) {
            null -> manifest.pendingUri
            com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT -> manifest.portraitPendingUri
            com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE -> manifest.landscapePendingUri
        } ?: return abandon(sessionId, uri = null, side = side)

        val valid: Boolean = try {
            validatePending(uri)
        } catch (t: Throwable) {
            RovaLog.w("$TAG.recover: validatePending threw for $sessionId", t)
            false
        }
        if (!valid) {
            // The probe failed — the row is corrupt/missing/unopenable.
            // This is the ONLY recovery path that may delete the row.
            return abandon(sessionId, uri = uri, side = side)
        }

        // INVARIANT (NO-GO patch from commit-4 review): from this point
        // on, recovery MUST NOT delete the row. `validatePending`
        // proved the artifact is intact; any subsequent finalize anomaly
        // is interpreted conservatively, because the on-disk state may
        // already be a successfully-published artifact whose FINALIZED
        // manifest write was lost in a prior crash. Deleting it would
        // destroy user data.
        return when (val r = finalizePendingRow(uri)) {
            // `IS_PENDING` was 1, just transitioned to 0 — fresh finalize.
            Tier1FinalizeResult.Finalized ->
                writeFinalizedAfterValidatedRow(sessionId, uri, side)

            // `update` returned 0 rows. Combined with `validatePending`
            // having just succeeded, the only consistent reading is "the
            // row was already at IS_PENDING=0" (a prior successful
            // publish whose manifest write was lost). Trust the artifact;
            // catch the manifest up to the on-disk reality.
            Tier1FinalizeResult.NoRowsUpdated -> {
                RovaLog.d(
                    "$TAG.recover: finalizePendingRow reported 0 rows for $sessionId — " +
                        "treating as already-finalized (lost manifest write); not deleting row"
                )
                writeFinalizedAfterValidatedRow(sessionId, uri, side)
            }

            // The seam threw. The row is intact (validatePending proved
            // it); the failure is in the MediaStore op, not on the
            // artifact. Defer to the next cold launch — DO NOT delete
            // the row here.
            is Tier1FinalizeResult.Failed -> {
                RovaLog.w(
                    "$TAG.recover: finalizePendingRow threw for $sessionId after " +
                        "validatePending=true; deferring to next cold launch (row retained)",
                    r.cause
                )
                RecoveryResult.RetryableFailure("finalizePendingRow", r.cause)
            }
        }
    }

    private suspend fun writeFinalizedAfterValidatedRow(
        sessionId: String,
        uri: String,
        side: com.aritr.rova.service.dualrecord.VideoSide? = null
    ): RecoveryResult {
        // Phase 6.1b T14: when `side != null` route to the per-side
        // mutator — shared `exportState` is NOT advanced here; T13's
        // caller writes the final shared `exportState` after both sides
        // settle. Tier 1's MediaStore URI is recorded as the per-side
        // publicTargetPath (consistent with T12's per-side export path).
        val r = if (side == null) {
            sessionStore.setExportFinalized(sessionId, clearPrivateTempPath = false)
        } else {
            sessionStore.setExportFinalizedForSide(
                sessionId = sessionId,
                side = side,
                publicTargetPath = uri,
                clearPrivateTempPath = false
            )
        }
        return when (r) {
            is ExportMutationResult.Wrote ->
                RecoveryResult.Resumed(
                    ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false)
                )
            is ExportMutationResult.UnknownSession ->
                RecoveryResult.UnknownSession(r.sessionId)
            is ExportMutationResult.Failed ->
                RecoveryResult.ManifestWriteFailed(r.cause)
        }
    }

    // ─── Cleanup helpers ────────────────────────────────────────────

    private suspend fun cleanupAndMap(
        sessionId: String,
        naturalFailure: ExportResult,
        uri: String?,
        side: com.aritr.rova.service.dualrecord.VideoSide? = null
    ): ExportResult {
        if (uri != null) safeDeleteRow(uri)
        // Phase 6.1b T12: when `side != null`, do NOT touch the shared
        // `exportState` via `setExportFailed` — T13's caller owns failure
        // attribution after both sides settle. The pending row was
        // already deleted above; the per-side `pendingUri` field is left
        // populated for forensics so T13 can detect the per-side failure.
        if (side != null) return naturalFailure
        return when (val r = sessionStore.setExportFailed(sessionId)) {
            is ExportMutationResult.Wrote -> naturalFailure
            is ExportMutationResult.UnknownSession ->
                ExportResult.UnknownSession(r.sessionId)
            is ExportMutationResult.Failed ->
                ExportResult.ManifestWriteFailed("setExportFailed", r.cause)
        }
    }

    private suspend fun abandon(
        sessionId: String,
        uri: String?,
        side: com.aritr.rova.service.dualrecord.VideoSide? = null
    ): RecoveryResult {
        if (uri != null) safeDeleteRow(uri)
        // Phase 6.1b T14: when `side != null`, SKIP the shared
        // `setExportFailed` write — T13's caller owns failure attribution
        // after both sides settle. The row deletion above still runs
        // unconditionally.
        if (side != null) return RecoveryResult.Abandoned
        return when (val r = sessionStore.setExportFailed(sessionId)) {
            is ExportMutationResult.Wrote -> RecoveryResult.Abandoned
            is ExportMutationResult.UnknownSession ->
                RecoveryResult.UnknownSession(r.sessionId)
            is ExportMutationResult.Failed ->
                RecoveryResult.ManifestWriteFailed(r.cause)
        }
    }

    private suspend fun safeDeleteRow(uri: String) {
        try {
            if (!deletePendingRow(uri)) {
                RovaLog.w("$TAG.safeDeleteRow: deletePendingRow returned false for $uri")
            }
        } catch (t: Throwable) {
            RovaLog.w("$TAG.safeDeleteRow: deletePendingRow threw for $uri", t)
        }
    }

    private companion object {
        const val TAG = "Tier1Exporter"
    }
}
