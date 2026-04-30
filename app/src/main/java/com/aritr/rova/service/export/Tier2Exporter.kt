package com.aritr.rova.service.export

import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import java.io.File

/**
 * Phase 1.7 commit-3 (ADR 0003 §"Recovery routing", Tier 2 column).
 * API 26–28 export pipeline + cold-launch recovery.
 *
 * Tier 2 constraints (per ADR 0003 §"Forbidden combinations" + §"FD
 * Mode Amendment"):
 * - `MediaMuxer(String, ...)` only — `MediaMuxer(FileDescriptor)` is
 *   reachable at API 26+ but ADR 0003 reserves FD muxing for Tier 1.
 *   Tier 2 deliberately matches Tier 3's path-based mux shape so a
 *   single shared core ([PreQExportCore]) handles both pre-Q tiers.
 * - No `MediaStore` ops — `IS_PENDING` is API 29+; Tier 2 publishes
 *   via `Environment.DIRECTORY_MOVIES/Rova/<name>.mp4` plus
 *   `MediaScannerConnection.scanFile`.
 * - `<name>.mp4.part` + `renameTo` is the publish atom.
 *
 * Behavior is identical to [Tier3Exporter] — both delegate to
 * [PreQExportCore]. The only Tier-2-specific bit is the
 * recovery-guard tier ([ExportTier.TIER2_API26_28]) and the log tag
 * for failure attribution.
 *
 * Permissions: Tier 2 publishes via legacy
 * `Environment.getExternalStoragePublicDirectory(DIRECTORY_MOVIES)`
 * which requires `WRITE_EXTERNAL_STORAGE` (already declared with
 * `android:maxSdkVersion="28"` in AndroidManifest.xml). Permission
 * resolution and grant flow live in the caller (commit 7
 * `performMerge` replacement); the exporter assumes the public dir
 * is writable.
 *
 * Live-export sequence (`export(...)`) and recovery (`recover(manifest)`)
 * — see [PreQExportCore] KDoc for the full step list and Cases A/B/C/D.
 *
 * NOT in scope for commit 3:
 * - `markTerminated(COMPLETED, NONE)` — caller writes the terminal
 *   value based on [ExportResult].
 * - Live export from inside [com.aritr.rova.service.RovaRecordingService]
 *   — wired in commit 7.
 * - Tier 1 (commit 4) and the orphan sweep (commit 5).
 */
class Tier2Exporter(
    sessionStore: SessionStore,
    mediaScanWaiter: MediaScanWaiter,
    mux: suspend (segments: List<File>, output: File) -> Unit,
    copyFile: (src: File, dst: File) -> Unit = { src, dst ->
        src.copyTo(dst, overwrite = true)
    },
    renameFile: (src: File, dst: File) -> Boolean = { src, dst ->
        src.renameTo(dst)
    },
    deleteFile: (file: File) -> Boolean = { it.delete() }
) {

    private val core = PreQExportCore(
        sessionStore = sessionStore,
        mediaScanWaiter = mediaScanWaiter,
        mux = mux,
        copyFile = copyFile,
        renameFile = renameFile,
        deleteFile = deleteFile,
        tag = "Tier2Exporter",
        expectedTier = ExportTier.TIER2_API26_28
    )

    suspend fun export(
        sessionId: String,
        segments: List<File>,
        privateTempFile: File,
        publicTargetFile: File
    ): ExportResult = core.export(sessionId, segments, privateTempFile, publicTargetFile)

    suspend fun recover(manifest: SessionManifest): RecoveryResult = core.recover(manifest)
}
