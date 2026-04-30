package com.aritr.rova.service.export

import com.aritr.rova.data.ExportMutationResult
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import com.aritr.rova.utils.RovaLog
import java.io.File

/**
 * Phase 1.7 commit-2 (ADR 0003 §"Recovery routing", Tier 3 column).
 * API 24–25 export pipeline + cold-launch recovery, owned by a single
 * unit so the publish-and-finalize core is shared between live export
 * and recovery resumption.
 *
 * Tier 3 constraints (per ADR 0003 §"Forbidden combinations"):
 * - `MediaMuxer(String, ...)` only — no `FileDescriptor` constructor
 *   (API 26+).
 * - No `MediaStore` ops — `IS_PENDING` is API 29+; Tier 3 publishes via
 *   `Environment.DIRECTORY_MOVIES/Rova/<name>.mp4` plus
 *   `MediaScannerConnection.scanFile`.
 * - `<name>.mp4.part` + `renameTo` is the publish atom.
 *
 * Live-export sequence (`export(...)`):
 * 1. `setExportPrivateTarget` (manifest commit point A: both pointer
 *    fields + `exportState = MUXING` in one atomic write).
 * 2. Mux segments → `privateTempFile`.
 * 3. `setExportCopying` (`exportState = COPYING`).
 * 4. Copy `privateTempFile` → `<publicTarget>.part`.
 * 5. Rename `.part` → `publicTargetFile`.
 * 6. Bounded [MediaScanWaiter.scanAndWait] (default 5 s).
 * 7a. Scan callback fired: try delete `privateTempFile`. On delete
 *     success → `setMediaScanCompleted` then
 *     `setExportFinalized(clearPrivateTempPath = true)`. On delete
 *     failure → `setExportFinalized(clearPrivateTempPath = false)`;
 *     `mediaScanCompleted` stays `false` so cold-launch recovery's
 *     deferred-scan branch re-fires the scan + delete on the next
 *     launch (idempotent).
 * 7b. Scan timed out: `setExportFinalized(clearPrivateTempPath = false)`;
 *     `privateTempPath` retained as recovery fuel.
 *
 * Recovery (`recover(manifest)`) handles the four disk-state cases
 * from ADR 0003 §"Recovery routing":
 * - **Case A** — `publicTargetFile` exists with non-zero size: rename
 *   succeeded but a crash happened before scan callback / temp cleanup.
 *   Re-fire scan, then run [finalize].
 * - **Case B** — `publicTargetFile` missing, `<name>.mp4.part` exists:
 *   copy was in flight. Delete the `.part`. If `privateTempFile` is
 *   readable, resume from copy step. Otherwise abandon.
 * - **Case C** — only `privateTempFile` exists, readable: mux completed
 *   but copy never started. Resume from copy step.
 * - **Case D** — nothing usable: `setExportFailed`, abandon.
 *
 * Delivery order in [export]/[recover]:
 * - Manifest writes are read-then-write on the serial
 *   [SessionStore.persistDispatcher] inside the mutator helpers. Live
 *   export and recovery never re-enter the same session concurrently
 *   (the cold-launch recovery routine runs under `startupMutex`, the
 *   live service is the only registered controller for its sessionId
 *   under `ServiceController`).
 *
 * Injectable seams (test hooks; production callers wire defaults):
 * - [mux] — segment-to-MP4 mux operation. Production wraps
 *   `VideoMerger.mergeSegments`. Tests substitute file-write lambdas.
 * - [copyFile] / [renameFile] / [deleteFile] — file-system ops with
 *   default real-FS implementations; tests override to drive copy /
 *   rename / delete failure paths deterministically.
 *
 * NOT in scope for commit 2:
 * - `markTerminated(COMPLETED, NONE)` — caller (`performMerge`
 *   replacement, commit 7) writes the terminal value based on
 *   [ExportResult].
 * - Live export from inside [com.aritr.rova.service.RovaRecordingService]
 *   — wired in commit 7.
 * - Tier 1 / Tier 2 dispatch — separate exporters in commits 3 / 4.
 */
class Tier3Exporter(
    private val sessionStore: SessionStore,
    private val mediaScanWaiter: MediaScanWaiter,
    private val mux: suspend (segments: List<File>, output: File) -> Unit,
    private val copyFile: (src: File, dst: File) -> Unit = { src, dst ->
        src.copyTo(dst, overwrite = true)
    },
    private val renameFile: (src: File, dst: File) -> Boolean = { src, dst ->
        src.renameTo(dst)
    },
    private val deleteFile: (file: File) -> Boolean = { it.delete() }
) {

    /**
     * Live Tier 3 export. See class KDoc for the full sequence.
     *
     * The caller is responsible for resolving [privateTempFile]
     * (typically `videosRoot/<sessionId>/export/<sessionId>.mp4`) and
     * [publicTargetFile] (typically
     * `Environment.getExternalStoragePublicDirectory(DIRECTORY_MOVIES)/
     * Rova/<displayName>.mp4`). The exporter does not own the path
     * resolution because Tier-specific path conventions live with the
     * caller's tier dispatch.
     */
    suspend fun export(
        sessionId: String,
        segments: List<File>,
        privateTempFile: File,
        publicTargetFile: File
    ): ExportResult {
        // 1. Manifest commit point A.
        manifestWrite("setExportPrivateTarget") {
            sessionStore.setExportPrivateTarget(
                sessionId,
                privateTempFile.absolutePath,
                publicTargetFile.absolutePath
            )
        }?.let { return it }

        // 2. Mux to private temp.
        privateTempFile.parentFile?.mkdirs()
        try {
            mux(segments, privateTempFile)
        } catch (t: Throwable) {
            RovaLog.w("Tier3Exporter: mux failed for $sessionId", t)
            return cleanupAndMap(
                sessionId,
                naturalFailure = ExportResult.MuxFailed(t),
                privateTempFile = privateTempFile,
                partFile = partFileFor(publicTargetFile),
                publicTargetFile = publicTargetFile
            )
        }

        // 3-7. Publish-and-finalize (shared with recovery cases B/C).
        return publishAndFinalize(sessionId, privateTempFile, publicTargetFile)
    }

    /**
     * Cold-launch recovery for a Tier 3 manifest with
     * `exportState ∈ {MUXING, COPYING, FINALIZED}` AND
     * `terminated != COMPLETED`, OR the deferred-scan combination
     * (`exportState == FINALIZED && mediaScanCompleted == false &&
     * publicTargetPath != null`).
     *
     * Caller is responsible for filtering manifests per the routing
     * rules — this method assumes the manifest is in scope.
     */
    suspend fun recover(manifest: SessionManifest): RecoveryResult {
        require(manifest.exportTier == ExportTier.TIER3_API24_25) {
            "Tier3Exporter.recover called with tier ${manifest.exportTier}"
        }
        val sessionId = manifest.sessionId
        val privateTempPath = manifest.privateTempPath
        val publicTargetPath = manifest.publicTargetPath

        // Pre-commit-point death: nothing was written. Treat as Case D
        // (abandon). The Phase 1.7 routing guarantees we don't enter
        // recover() unless exportState ∈ {MUXING, COPYING, FINALIZED}, so
        // both pointers being null in that range is an inconsistent
        // manifest — abandon and let cleanup handle it.
        if (privateTempPath == null || publicTargetPath == null) {
            return abandon(sessionId, privateTempFile = null, partFile = null, publicTargetFile = null)
        }

        val privateTempFile = File(privateTempPath)
        val publicTargetFile = File(publicTargetPath)
        val partFile = partFileFor(publicTargetFile)

        // Case A: public target on disk with content.
        if (publicTargetFile.exists() && publicTargetFile.length() > 0L) {
            val scanCompleted = mediaScanWaiter.scanAndWait(publicTargetFile)
            return RecoveryResult.Resumed(finalize(sessionId, privateTempFile, scanCompleted))
        }

        // Case B: .part on disk; rename never landed. Best-effort .part
        // delete (an orphan .part would be invisible to gallery scanners
        // — the suffix excludes it from typical video matchers — but is
        // still wasted bytes).
        if (partFile.exists()) {
            if (!safeDelete(partFile)) {
                RovaLog.w("Tier3Exporter.recover: failed to delete stale .part ${partFile.absolutePath}")
            }
            // Fall through: if privateTempFile is usable we resume; else
            // we land in Case D below.
        }

        // Case C: privateTempFile readable → resume publish-and-finalize.
        if (privateTempFile.exists() && privateTempFile.length() > 0L) {
            return RecoveryResult.Resumed(
                publishAndFinalize(sessionId, privateTempFile, publicTargetFile)
            )
        }

        // Case D: nothing usable.
        return abandon(sessionId, privateTempFile, partFile, publicTargetFile)
    }

    // ─── Shared core ────────────────────────────────────────────────

    private suspend fun publishAndFinalize(
        sessionId: String,
        privateTempFile: File,
        publicTargetFile: File
    ): ExportResult {
        // 3. exportState = COPYING.
        manifestWrite("setExportCopying") {
            sessionStore.setExportCopying(sessionId)
        }?.let { return it }

        val partFile = partFileFor(publicTargetFile)

        // 4. Copy private → .part.
        publicTargetFile.parentFile?.mkdirs()
        try {
            copyFile(privateTempFile, partFile)
        } catch (t: Throwable) {
            RovaLog.w("Tier3Exporter: copy failed for $sessionId", t)
            return cleanupAndMap(
                sessionId,
                naturalFailure = ExportResult.CopyFailed(t),
                privateTempFile = privateTempFile,
                partFile = partFile,
                publicTargetFile = publicTargetFile
            )
        }

        // 5. Rename .part → final.
        if (!renameFile(partFile, publicTargetFile)) {
            RovaLog.w(
                "Tier3Exporter: renameTo returned false for $sessionId; " +
                    "${partFile.absolutePath} → ${publicTargetFile.absolutePath}"
            )
            return cleanupAndMap(
                sessionId,
                naturalFailure = ExportResult.RenameFailed,
                privateTempFile = privateTempFile,
                partFile = partFile,
                publicTargetFile = publicTargetFile
            )
        }

        // 6. Bounded scan.
        val scanCompleted = mediaScanWaiter.scanAndWait(publicTargetFile)

        // 7. Finalize.
        return finalize(sessionId, privateTempFile, scanCompleted)
    }

    /**
     * Phase 1.7 commit-2 finalize logic (also reused by recovery Case A).
     *
     * - On scan callback fired: attempt private-temp delete; if delete
     *   succeeds, write `setMediaScanCompleted` then
     *   `setExportFinalized(clearPrivateTempPath = true)`. If delete
     *   fails, write only `setExportFinalized(clearPrivateTempPath =
     *   false)` — `mediaScanCompleted` stays `false` so the next cold
     *   launch's deferred-scan branch will re-fire scan + retry delete
     *   (idempotent at every layer).
     * - On scan timeout: `setExportFinalized(clearPrivateTempPath =
     *   false)`; private temp + `mediaScanCompleted = false` retained
     *   as recovery fuel.
     */
    private suspend fun finalize(
        sessionId: String,
        privateTempFile: File,
        scanCompleted: Boolean
    ): ExportResult {
        if (!scanCompleted) {
            manifestWrite("setExportFinalized(retain)") {
                sessionStore.setExportFinalized(sessionId, clearPrivateTempPath = false)
            }?.let { return it }
            return ExportResult.Success(mediaScanCompleted = false, privateTempRetained = true)
        }

        val deleted = safeDelete(privateTempFile)
        if (!deleted) {
            RovaLog.w(
                "Tier3Exporter.finalize: private temp delete failed for ${privateTempFile.absolutePath}; " +
                    "leaving manifest mediaScanCompleted=false for next-launch retry"
            )
            manifestWrite("setExportFinalized(retain-after-delete-fail)") {
                sessionStore.setExportFinalized(sessionId, clearPrivateTempPath = false)
            }?.let { return it }
            return ExportResult.Success(mediaScanCompleted = false, privateTempRetained = true)
        }

        manifestWrite("setMediaScanCompleted") {
            sessionStore.setMediaScanCompleted(sessionId)
        }?.let { return it }
        manifestWrite("setExportFinalized(clear)") {
            sessionStore.setExportFinalized(sessionId, clearPrivateTempPath = true)
        }?.let { return it }
        return ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false)
    }

    // ─── Cleanup / helpers ──────────────────────────────────────────

    /**
     * Best-effort file deletes BEFORE the manifest write (per the spec
     * "pointers cleared only after caller-side cleanup attempt") and
     * then `setExportFailed`. Returns the manifest mutation result so
     * callers can map it to the correct outer-result variant —
     * **swallowing this result would let the exporter report
     * `MuxFailed` / `CopyFailed` / `RenameFailed` / `Abandoned` while
     * the manifest is actually still in `MUXING` / `COPYING`, which
     * would mislead cold-launch recovery into the wrong path.**
     *
     * Delete failures are logged but never throw; the manifest write
     * still happens because pointer fields must be cleared even when
     * the on-disk artifacts couldn't be removed (otherwise recovery
     * would try to recover from non-existent files).
     */
    private suspend fun cleanupOnFailure(
        sessionId: String,
        privateTempFile: File?,
        partFile: File?,
        publicTargetFile: File?
    ): ExportMutationResult {
        listOfNotNull(privateTempFile, partFile, publicTargetFile)
            .filter { it.exists() }
            .forEach { f ->
                if (!safeDelete(f)) {
                    RovaLog.w("Tier3Exporter.cleanupOnFailure: failed to delete ${f.absolutePath}")
                }
            }
        return sessionStore.setExportFailed(sessionId)
    }

    /**
     * Run cleanup, then map the manifest write outcome to the right
     * outer-export variant. `Wrote` → caller's natural failure
     * (`MuxFailed` / `CopyFailed` / `RenameFailed`). `UnknownSession` →
     * `ExportResult.UnknownSession` (manifest gone mid-export).
     * `Failed` → `ExportResult.ManifestWriteFailed` (caller must defer
     * to cold-launch recovery — the manifest is in an indeterminate
     * state, NOT necessarily `FAILED`).
     */
    private suspend fun cleanupAndMap(
        sessionId: String,
        naturalFailure: ExportResult,
        privateTempFile: File?,
        partFile: File?,
        publicTargetFile: File?
    ): ExportResult = when (
        val r = cleanupOnFailure(sessionId, privateTempFile, partFile, publicTargetFile)
    ) {
        is ExportMutationResult.Wrote -> naturalFailure
        is ExportMutationResult.UnknownSession -> ExportResult.UnknownSession(r.sessionId)
        is ExportMutationResult.Failed -> ExportResult.ManifestWriteFailed("setExportFailed", r.cause)
    }

    /**
     * Recovery counterpart of [cleanupAndMap]. `Wrote` →
     * `RecoveryResult.Abandoned` (the recovery contract). Other
     * outcomes map to the equivalent `RecoveryResult` variants so
     * `Abandoned` is emitted **only** when `setExportFailed` actually
     * landed.
     */
    private suspend fun abandon(
        sessionId: String,
        privateTempFile: File?,
        partFile: File?,
        publicTargetFile: File?
    ): RecoveryResult = when (
        val r = cleanupOnFailure(sessionId, privateTempFile, partFile, publicTargetFile)
    ) {
        is ExportMutationResult.Wrote -> RecoveryResult.Abandoned
        is ExportMutationResult.UnknownSession -> RecoveryResult.UnknownSession(r.sessionId)
        is ExportMutationResult.Failed -> RecoveryResult.ManifestWriteFailed(r.cause)
    }

    private fun safeDelete(file: File): Boolean = try {
        if (!file.exists()) true else deleteFile(file)
    } catch (t: Throwable) {
        RovaLog.w("Tier3Exporter.safeDelete: throw on ${file.absolutePath}", t)
        false
    }

    private fun partFileFor(publicTargetFile: File): File =
        File(publicTargetFile.parentFile, "${publicTargetFile.name}.part")

    /**
     * Map [ExportMutationResult] to either `null` (continue) or the
     * caller's early-return [ExportResult]. Centralizes the
     * UnknownSession / ManifestWriteFailed propagation.
     */
    private suspend fun manifestWrite(
        phase: String,
        block: suspend () -> ExportMutationResult
    ): ExportResult? = when (val r = block()) {
        is ExportMutationResult.Wrote -> null
        is ExportMutationResult.UnknownSession -> ExportResult.UnknownSession(r.sessionId)
        is ExportMutationResult.Failed -> ExportResult.ManifestWriteFailed(phase, r.cause)
    }
}
