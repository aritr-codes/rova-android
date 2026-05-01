package com.aritr.rova.service.export

import com.aritr.rova.data.ExportMutationResult
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import com.aritr.rova.utils.RovaLog
import java.io.File

/**
 * Phase 1.7 commit-3 — shared publish-and-finalize core for pre-Q
 * tiers (Tier 2 / Tier 3) per ADR 0003 §"Recovery routing".
 *
 * Both Tier 2 (API 26–28) and Tier 3 (API 24–25) follow the same
 * export shape: private `MediaMuxer(String, ...)` mux + public
 * `<name>.mp4.part` + `renameTo` + bounded `MediaScanWaiter.scanAndWait`.
 * ADR 0003 §"FD Mode Amendment" plus the Phase 1.7 design review
 * explicitly forbid `MediaMuxer(FileDescriptor)` on Tier 2 even
 * though API 26 supports it — both pre-Q tiers consume this same core,
 * parameterized only by the expected [ExportTier] for the recovery
 * guard and a log [tag] so failures attribute to the calling tier.
 *
 * Tier 1 dispatch does NOT consume this core — its commit-point
 * ordering ([SessionStore.setExportPending] before the muxer opens
 * the pending FD) and its `MediaStore` finalize step are
 * structurally different.
 *
 * Internal to `service/export` per the Phase 1.7 commit-3 spec
 * ("Any shared helper must stay internal/private to
 * service/export"). Callers (Tier2Exporter, Tier3Exporter) are
 * thin wrappers in the same package; nothing outside this package
 * should reference [PreQExportCore] directly.
 *
 * Behavior is identical to the commit-2 Tier 3 implementation; the
 * only parameterization is the tier-mismatch error and the log
 * `[tag]:` prefix. The result-bearing cleanup contract from commit
 * 2 is preserved: `cleanupOnFailure` returns the
 * [ExportMutationResult] from `setExportFailed`, and the live and
 * recovery paths map it through [cleanupAndMap] / [abandon] so the
 * outer result never lies about whether the manifest is actually in
 * `FAILED`.
 */
internal class PreQExportCore(
    private val sessionStore: SessionStore,
    private val mediaScanWaiter: MediaScanWaiter,
    private val mux: suspend (segments: List<File>, output: File) -> Unit,
    private val copyFile: (src: File, dst: File) -> Unit,
    private val renameFile: (src: File, dst: File) -> Boolean,
    private val deleteFile: (file: File) -> Boolean,
    private val tag: String,
    private val expectedTier: ExportTier
) {

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
            RovaLog.w("$tag: mux failed for $sessionId", t)
            return cleanupAndMap(
                sessionId,
                naturalFailure = ExportResult.MuxFailed(t),
                privateTempFile = privateTempFile,
                partFile = partFileFor(publicTargetFile)
            )
        }

        // 3-7. Publish-and-finalize (shared with recovery cases B/C).
        return publishAndFinalize(sessionId, privateTempFile, publicTargetFile)
    }

    suspend fun recover(manifest: SessionManifest): RecoveryResult {
        require(manifest.exportTier == expectedTier) {
            "$tag.recover called with tier ${manifest.exportTier} (expected $expectedTier)"
        }
        val sessionId = manifest.sessionId
        val privateTempPath = manifest.privateTempPath
        val publicTargetPath = manifest.publicTargetPath

        if (privateTempPath == null || publicTargetPath == null) {
            return abandon(sessionId, privateTempFile = null, partFile = null)
        }

        val privateTempFile = File(privateTempPath)
        val publicTargetFile = File(publicTargetPath)
        val partFile = partFileFor(publicTargetFile)

        // Case A: public target on disk with content.
        if (publicTargetFile.exists() && publicTargetFile.length() > 0L) {
            val scanCompleted = mediaScanWaiter.scanAndWait(publicTargetFile)
            return RecoveryResult.Resumed(finalize(sessionId, privateTempFile, scanCompleted))
        }

        // Case B: stale .part — best-effort delete, then fall through.
        if (partFile.exists()) {
            if (!safeDelete(partFile)) {
                RovaLog.w("$tag.recover: failed to delete stale .part ${partFile.absolutePath}")
            }
        }

        // Case C: privateTempFile readable → resume publish-and-finalize.
        if (privateTempFile.exists() && privateTempFile.length() > 0L) {
            return RecoveryResult.Resumed(
                publishAndFinalize(sessionId, privateTempFile, publicTargetFile)
            )
        }

        // Case D: nothing usable.
        return abandon(sessionId, privateTempFile, partFile)
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
            RovaLog.w("$tag: copy failed for $sessionId", t)
            return cleanupAndMap(
                sessionId,
                naturalFailure = ExportResult.CopyFailed(t),
                privateTempFile = privateTempFile,
                partFile = partFile
            )
        }

        // 5. Rename .part → final.
        if (!renameFile(partFile, publicTargetFile)) {
            RovaLog.w(
                "$tag: renameTo returned false for $sessionId; " +
                    "${partFile.absolutePath} → ${publicTargetFile.absolutePath}"
            )
            return cleanupAndMap(
                sessionId,
                naturalFailure = ExportResult.RenameFailed,
                privateTempFile = privateTempFile,
                partFile = partFile
            )
        }

        // 6. Bounded scan.
        val scanCompleted = mediaScanWaiter.scanAndWait(publicTargetFile)

        // 7. Finalize.
        return finalize(sessionId, privateTempFile, scanCompleted)
    }

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
                "$tag.finalize: private temp delete failed for ${privateTempFile.absolutePath}; " +
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
     * Phase 1.7 commit-7 (NO-GO patch round 1, blocker 1): the cleanup
     * deletion list deliberately EXCLUDES `publicTargetFile`. The
     * publicTargetFile becomes "ours" only after a successful
     * `renameTo(...)` (the publish atom); from that point on we are on
     * the success path and never enter cleanup. Every code path that
     * routes here either never touched `publicTargetFile` (mux failed,
     * copy failed) or saw `renameTo` return false (we never created the
     * file). Including it in the deletion list would risk wiping a
     * pre-existing user video on filename collision — see
     * [ExportPipeline.exportPreQ]'s collision-avoidance probe partner.
     */
    private suspend fun cleanupOnFailure(
        sessionId: String,
        privateTempFile: File?,
        partFile: File?
    ): ExportMutationResult {
        listOfNotNull(privateTempFile, partFile)
            .filter { it.exists() }
            .forEach { f ->
                if (!safeDelete(f)) {
                    RovaLog.w("$tag.cleanupOnFailure: failed to delete ${f.absolutePath}")
                }
            }
        return sessionStore.setExportFailed(sessionId)
    }

    private suspend fun cleanupAndMap(
        sessionId: String,
        naturalFailure: ExportResult,
        privateTempFile: File?,
        partFile: File?
    ): ExportResult = when (
        val r = cleanupOnFailure(sessionId, privateTempFile, partFile)
    ) {
        is ExportMutationResult.Wrote -> naturalFailure
        is ExportMutationResult.UnknownSession -> ExportResult.UnknownSession(r.sessionId)
        is ExportMutationResult.Failed -> ExportResult.ManifestWriteFailed("setExportFailed", r.cause)
    }

    private suspend fun abandon(
        sessionId: String,
        privateTempFile: File?,
        partFile: File?
    ): RecoveryResult = when (
        val r = cleanupOnFailure(sessionId, privateTempFile, partFile)
    ) {
        is ExportMutationResult.Wrote -> RecoveryResult.Abandoned
        is ExportMutationResult.UnknownSession -> RecoveryResult.UnknownSession(r.sessionId)
        is ExportMutationResult.Failed -> RecoveryResult.ManifestWriteFailed(r.cause)
    }

    private fun safeDelete(file: File): Boolean = try {
        if (!file.exists()) true else deleteFile(file)
    } catch (t: Throwable) {
        RovaLog.w("$tag.safeDelete: throw on ${file.absolutePath}", t)
        false
    }

    private fun partFileFor(publicTargetFile: File): File =
        File(publicTargetFile.parentFile, "${publicTargetFile.name}.part")

    private suspend fun manifestWrite(
        phase: String,
        block: suspend () -> ExportMutationResult
    ): ExportResult? = when (val r = block()) {
        is ExportMutationResult.Wrote -> null
        is ExportMutationResult.UnknownSession -> ExportResult.UnknownSession(r.sessionId)
        is ExportMutationResult.Failed -> ExportResult.ManifestWriteFailed(phase, r.cause)
    }
}
