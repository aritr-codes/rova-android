package com.aritr.rova.service.export

import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.SessionStore
import java.io.File

/**
 * Phase 1.7 commit-2 (ADR 0003 §"Recovery routing", Tier 3 column).
 * API 24–25 export pipeline + cold-launch recovery.
 *
 * Tier 3 constraints (per ADR 0003 §"Forbidden combinations"):
 * - `MediaMuxer(String, ...)` only — no `FileDescriptor` constructor
 *   (API 26+ — Tier 3 cannot reach that API band).
 * - No `MediaStore` ops — `IS_PENDING` is API 29+; Tier 3 publishes via
 *   `Environment.DIRECTORY_MOVIES/Rova/<name>.mp4` plus
 *   `MediaScannerConnection.scanFile`.
 * - `<name>.mp4.part` + `renameTo` is the publish atom.
 *
 * **Implementation note (Phase 1.7 commit-3):** the publish-and-finalize
 * core is shared with [Tier2Exporter] via [PreQExportCore]. This class
 * is now a thin wrapper that pins [ExportTier.TIER3_API24_25] as the
 * recovery-guard tier. Behavior is unchanged from commit 2 — the
 * extraction is pure code motion.
 *
 * Live-export sequence (`export(...)`) and recovery (`recover(manifest)`)
 * — see [PreQExportCore] KDoc for the full step list and Cases A/B/C/D.
 *
 * Injectable seams (test hooks; production callers wire defaults):
 * - [mux] — segment-to-MP4 mux operation. Production wraps
 *   `VideoMerger.mergeSegments` (commit 7).
 * - [copyFile] / [renameFile] / [deleteFile] — file-system ops with
 *   default real-FS implementations; tests override to drive copy /
 *   rename / delete failure paths deterministically.
 *
 * NOT in scope for commit 3:
 * - `markTerminated(COMPLETED, NONE)` — caller (`performMerge`
 *   replacement, commit 7) writes the terminal value based on
 *   [ExportResult].
 * - Live export from inside [com.aritr.rova.service.RovaRecordingService]
 *   — wired in commit 7.
 */
class Tier3Exporter(
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
        tag = "Tier3Exporter",
        expectedTier = ExportTier.TIER3_API24_25
    )

    suspend fun export(
        sessionId: String,
        segments: List<File>,
        privateTempFile: File,
        publicTargetFile: File,
        side: com.aritr.rova.service.dualrecord.VideoSide? = null
    ): ExportResult = core.export(sessionId, segments, privateTempFile, publicTargetFile, side)

    suspend fun recover(
        manifest: SessionManifest,
        side: com.aritr.rova.service.dualrecord.VideoSide? = null
    ): RecoveryResult = core.recover(manifest, side)
}
