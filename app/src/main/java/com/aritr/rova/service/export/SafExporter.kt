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
    private val isPermissionHeld: () -> Boolean
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
        if (docUri != null && validateDocument(docUri)) {
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
            incrementRetry()
            return RecoveryResult.RetryableFailure("saf-recover-remux", t)
        }
        return RecoveryResult.Resumed(publish(privateTempFile))
    }

    // create doc → commit-before-stream → copy → validate → finalize(clear)
    private suspend fun publish(temp: File): ExportResult {
        val docUri: String = try {
            createDocument(displayName) ?: return classify(ExportResult.CopyFailed(failure()), null)
        } catch (t: Throwable) {
            return classify(ExportResult.CopyFailed(t), t)
        }
        displayNameOf(docUri)?.let { RovaLog.d("$TAG: created doc as '$it'") }
        commit(setSafTarget(docUri))?.let { return it }   // commit BEFORE the first byte
        try {
            copyFileToDocument(temp, docUri)
        } catch (t: Throwable) {
            RovaLog.w("$TAG: copy-to-SAF failed", t)
            safeDelete(docUri)
            return classify(ExportResult.CopyFailed(t), t)
        }
        if (!validateDocument(docUri)) {
            RovaLog.w("$TAG: SAF doc failed validation")
            safeDelete(docUri)
            return classify(ExportResult.CopyFailed(failure()), null)
        }
        return toExport(setFinalizedClear())
    }

    private suspend fun classify(transient: ExportResult, cause: Throwable?): ExportResult =
        if (!isPermissionHeld() || currentRetryCount() >= RETRY_BUDGET) permanent(cause)
        else { incrementRetry(); transient }

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

    private fun failure() = java.io.IOException("SAF publish failed")
}
