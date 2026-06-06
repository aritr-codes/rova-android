package com.aritr.rova.service.export

import com.aritr.rova.data.ExportMutationResult
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.utils.RovaLog
import java.io.File

/**
 * ADR-0024 — SAF export route. A destination variant of the pre-Q
 * (Tier 2/3) path: mux to a local private temp, then PUBLISH by copying
 * the bytes into the user-chosen SAF document (sequential openOutputStream
 * — no seeking, works on every provider), validate the doc, then finalize
 * (clearing privateTempPath). The muxer NEVER targets a SAF descriptor.
 *
 * Crash-safety rides the existing `privateTempPath` retention gate: the
 * field stays populated until the SAF artifact validates, so cleanup
 * cannot delete the segments first.
 *
 * Failure classification (ADR-0024):
 *  - PERMANENT — `isPermissionHeld()` is false OR the transient-retry
 *    budget (3) is exhausted → [ExportResult.SafFolderUnavailable] +
 *    `setFailed`. Surfaces WarningId.SAVE_FOLDER_UNAVAILABLE.
 *  - TRANSIENT — a throw while permission is still held and budget remains
 *    → MuxFailed/CopyFailed, manifest left in MUXING/COPYING (NO terminal
 *    write), retried next cold launch (`incrementRetry`).
 *
 * Pure: every Android op is an injected lambda (production wires them to
 * SafAndroidOps).
 */
internal class SafExporter(
    private val displayName: String,
    private val privateTempFile: File,
    private val setSafPrivateTemp: suspend (privateTempPath: String) -> ExportMutationResult,
    private val setSafTarget: suspend (docUri: String) -> ExportMutationResult,
    private val setFinalizedClear: suspend () -> ExportMutationResult,
    private val setFailed: suspend () -> ExportMutationResult,
    private val incrementRetry: suspend () -> ExportMutationResult,
    private val currentRetryCount: () -> Int,
    private val mux: suspend (segments: List<File>, output: File) -> Unit,
    private val createDocument: (displayName: String) -> String?,
    private val displayNameOf: (docUri: String) -> String?,
    private val copyFileToDocument: (src: File, docUri: String) -> Unit,
    private val validateDocument: (docUri: String) -> Boolean,
    private val deleteDocument: (docUri: String) -> Boolean,
    private val isPermissionHeld: () -> Boolean,
    // B4 storage reclaim — deletes the LOCAL private temp (app-internal
    // sessionDir file), distinct from `deleteDocument` (the SAF provider doc).
    private val deleteLocalTemp: (file: File) -> Boolean = { it.delete() }
) {
    private companion object { const val TAG = "SafExporter"; const val RETRY_BUDGET = 3 }

    /** Live export: mux [segments] to a local private temp, then publish to the SAF doc. */
    suspend fun export(sessionId: String, segments: List<File>): ExportResult {
        if (!isPermissionHeld()) return permanent(null)
        commit(setSafPrivateTemp(privateTempFile.absolutePath))?.let { return it }
        privateTempFile.parentFile?.mkdirs()
        try {
            mux(segments, privateTempFile)
        } catch (t: Throwable) {
            RovaLog.w("$TAG: mux failed for $sessionId", t)
            return classify(ExportResult.MuxFailed(t), t)
        }
        return publish(privateTempFile)
    }

    /**
     * Recovery — validate-before-delete. A committed `safTargetDocUri` that
     * already validates is the GOOD artifact (lost finalize write); finalize
     * it, never re-copy/delete. Otherwise re-mux from the AUTHORITATIVE
     * [segments] (not a possibly-partial temp) and re-publish.
     */
    suspend fun recover(manifest: SessionManifest, segments: List<File>): RecoveryResult {
        val docUri = manifest.safTargetDocUri
        if (docUri != null && validateSafely(docUri)) {
            // A prior run may have left the muxed private temp on disk before
            // crashing pre-finalize; reclaim it now the committed doc is proven good.
            safeDeleteLocal(privateTempFile)
            return toRecovery(setFinalizedClear())
        }
        if (!isPermissionHeld() || currentRetryCount() >= RETRY_BUDGET) {
            return RecoveryResult.Resumed(permanent(null))
        }
        try {
            privateTempFile.parentFile?.mkdirs()
            mux(segments, privateTempFile)
        } catch (t: Throwable) {
            RovaLog.w("$TAG.recover: re-mux failed for ${manifest.sessionId}", t)
            // classify() handles permission-revoked-mid-remux (permanent) + the retry bump
            return toRecoveryOf(classify(ExportResult.MuxFailed(t), t), "saf-recover-remux", t)
        }
        // [staleDocUri] = the OLD committed-but-invalid doc; reclaimed only
        // after the fresh re-publish durably validates (never before — see publish).
        return RecoveryResult.Resumed(publish(privateTempFile, staleDocUri = docUri))
    }

    // create doc → commit-before-stream → copy → validate → finalize(clear)
    //
    // [staleDocUri] is the previous committed SAF doc when this is a recovery
    // re-publish (it was invalid, so a brand-new doc is created here). It is
    // best-effort deleted ONLY after the replacement durably validates and only
    // when it differs from the new doc — otherwise a crash mid-copy would leave a
    // partial/zero-byte orphan in the user's folder (often the SD card), and the
    // provider's name-collision auto-rename would pile up `name (1).mp4` siblings.
    // Deleting only post-validation preserves validate-before-delete: a transient
    // provider error never destroys a not-yet-replaced artifact.
    private suspend fun publish(temp: File, staleDocUri: String? = null): ExportResult {
        val docUri: String = try {
            createDocument(displayName) ?: return classify(ExportResult.CopyFailed(failure()), null)
        } catch (t: Throwable) {
            return classify(ExportResult.CopyFailed(t), t)
        }
        displayNameOf(docUri)?.let { name -> RovaLog.d { "$TAG: created doc as '$name'" } }
        commit(setSafTarget(docUri))?.let { return it }   // commit BEFORE the first byte
        // A provider may throw on close/flush even after the bytes are durable, so the
        // KEEP/DELETE decision hinges on validation, not on whether copy threw
        // (validate-before-delete — never destroy a valid artifact).
        val copyError: Throwable? = try {
            copyFileToDocument(temp, docUri); null
        } catch (t: Throwable) {
            RovaLog.w("$TAG: copy-to-SAF threw (validating before any delete)", t); t
        }
        if (validateSafely(docUri)) {
            if (staleDocUri != null && staleDocUri != docUri) {
                RovaLog.d { "$TAG: reclaiming stale partial SAF doc after successful re-publish" }
                safeDelete(staleDocUri)
            }
            // B4 storage reclaim (parity with PreQExportCore.finalize): the SAF
            // doc validated and is durable in the user's folder, so the local
            // private temp is dead weight. Delete it BEFORE clearing
            // privateTempPath — a crash in between is safe (recovery re-validates
            // the committed doc and finalizes; the dangling path is harmless),
            // whereas clearing first then crashing would orphan the temp forever.
            safeDeleteLocal(temp)
            return toExport(setFinalizedClear())
        }
        RovaLog.w("$TAG: SAF doc failed validation; deleting partial")
        safeDelete(docUri)
        return classify(ExportResult.CopyFailed(copyError ?: failure()), copyError)
    }

    private suspend fun classify(transient: ExportResult, cause: Throwable?): ExportResult =
        if (!isPermissionHeld() || currentRetryCount() >= RETRY_BUDGET) {
            permanent(cause)
        } else when (val r = incrementRetry()) {
            is ExportMutationResult.Wrote -> transient
            is ExportMutationResult.UnknownSession -> ExportResult.UnknownSession(r.sessionId)
            is ExportMutationResult.Failed -> ExportResult.ManifestWriteFailed("incrementRetry", r.cause)
        }

    private suspend fun permanent(cause: Throwable?): ExportResult {
        when (val r = setFailed()) {
            is ExportMutationResult.Wrote -> {}
            is ExportMutationResult.UnknownSession -> return ExportResult.UnknownSession(r.sessionId)
            is ExportMutationResult.Failed -> return ExportResult.ManifestWriteFailed("setExportFailed", r.cause)
        }
        return ExportResult.SafFolderUnavailable(cause)
    }

    private fun toExport(r: ExportMutationResult): ExportResult = when (r) {
        is ExportMutationResult.Wrote -> ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false)
        is ExportMutationResult.UnknownSession -> ExportResult.UnknownSession(r.sessionId)
        is ExportMutationResult.Failed -> ExportResult.ManifestWriteFailed("setExportFinalized", r.cause)
    }

    private fun toRecoveryOf(export: ExportResult, phase: String, cause: Throwable?): RecoveryResult =
        when (export) {
            // RetryableFailure.cause is non-null; transient export results carry their own cause.
            is ExportResult.MuxFailed -> RecoveryResult.RetryableFailure(phase, export.cause)
            is ExportResult.CopyFailed -> RecoveryResult.RetryableFailure(phase, export.cause)
            else -> RecoveryResult.Resumed(export)
        }

    private fun toRecovery(r: ExportMutationResult): RecoveryResult = when (r) {
        is ExportMutationResult.Wrote -> RecoveryResult.Resumed(
            ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false))
        is ExportMutationResult.UnknownSession -> RecoveryResult.UnknownSession(r.sessionId)
        is ExportMutationResult.Failed -> RecoveryResult.ManifestWriteFailed(r.cause)
    }

    private fun commit(r: ExportMutationResult): ExportResult? = when (r) {
        is ExportMutationResult.Wrote -> null
        is ExportMutationResult.UnknownSession -> ExportResult.UnknownSession(r.sessionId)
        is ExportMutationResult.Failed -> ExportResult.ManifestWriteFailed("saf-commit", r.cause)
    }

    private fun safeDelete(docUri: String) {
        try { deleteDocument(docUri) } catch (t: Throwable) { RovaLog.w("$TAG: deleteDocument threw", t) }
    }

    /** Best-effort reclaim of the local private temp; a lingering file is non-fatal (the
     *  SAF doc is the durable artifact) and the session dir delete eventually sweeps it. */
    private fun safeDeleteLocal(file: File) {
        try {
            if (file.exists() && !deleteLocalTemp(file)) {
                RovaLog.w("$TAG: failed to delete local private temp ${file.absolutePath}")
            }
        } catch (t: Throwable) { RovaLog.w("$TAG: deleteLocalTemp threw", t) }
    }

    /** validateDocument can throw (e.g. SecurityException on revoked SAF permission); treat a throw as "not valid". */
    private fun validateSafely(docUri: String): Boolean =
        try { validateDocument(docUri) } catch (t: Throwable) { RovaLog.w("$TAG: validateDocument threw", t); false }

    private fun failure() = java.io.IOException("SAF publish failed")
}
