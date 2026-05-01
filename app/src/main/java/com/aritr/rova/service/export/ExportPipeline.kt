package com.aritr.rova.service.export

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.annotation.RequiresApi
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionStore
import com.aritr.rova.data.currentExportTier
import com.aritr.rova.utils.VideoMerger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 1.7 commit-7 (ADR 0003 §"Recovery routing" + §"FD Mode
 * Amendment"). The single live entry point into tier export — Tier 1
 * (`MediaStore` pending row + `MediaMuxer(FileDescriptor)`) on API 29+,
 * Tier 2 (`Environment.DIRECTORY_MOVIES` + `<name>.mp4.part` + `renameTo`)
 * on API 26–28, Tier 3 (same as Tier 2) on API 24–25.
 *
 * Single-entry rule: only [com.aritr.rova.service.RovaRecordingService.performMerge]
 * may invoke [export]. Enforced by the `checkExportPipelineSingleEntry`
 * lint — all VideoMerger mux callers must live under `service/export/`,
 * and `ExportPipeline.export(` may only appear in
 * `RovaRecordingService.kt` so the service-side terminal-write
 * invariants (ADR 0006 §"Terminal-Write Ordering" — `COMPLETED` is
 * written from `performMerge`, not from any exporter) cannot be
 * bypassed.
 *
 * Result-bearing: returns [ExportResult]. Caller dispatches:
 *  - [ExportResult.Success] → write `markTerminated(COMPLETED, NONE)`
 *    (live merge-success commit point per B7) when not user-stopped.
 *  - any failure variant → no terminal write; cold-launch recovery
 *    reconciles via [ExportRecoveryRunner] on the next process start.
 *
 * Tier 1 pendingUri lifecycle (live):
 *  1. `Tier1AndroidOps.insertPendingRow` — `IS_PENDING=1` row inserted.
 *  2. `SessionStore.setExportPending(uri)` — manifest commit point
 *     BEFORE the muxer opens the FD.
 *  3. `Tier1AndroidOps.withPendingFd(uri, "rw") { fd -> mux(fd) }` —
 *     `MediaMuxer(FileDescriptor)` against the seekable PFD per ADR 0003
 *     §FD Mode Amendment.
 *  4. `Tier1AndroidOps.finalizePendingRow` — `IS_PENDING=0` (publish).
 *  5. `SessionStore.setExportFinalized` — manifest catches up.
 *
 * Tier 2/3 pendingUri lifecycle (live): private mux to
 * `<sessionDir>/<displayName>.private` → `setExportPrivateTarget` →
 * `setExportCopying` → copy to `<DIRECTORY_MOVIES>/Rova/<name>.mp4.part`
 * → `renameTo` (publish atom) → bounded `MediaScanWaiter.scanAndWait` →
 * `setMediaScanCompleted` + `setExportFinalized(clearPrivateTempPath=true)`.
 */
internal object ExportPipeline {

    /**
     * Live tier-dispatched export. Returns the [ExportResult] for the
     * caller to consume — [ExportResult.Success] is the only variant
     * authorizing a terminal `markTerminated(COMPLETED, NONE)` write.
     *
     * [onProgress] receives mux-stage progress in [0.0, 1.0]; the
     * publish/finalize stages do not currently report incremental
     * progress (would require new seams).
     */
    suspend fun export(
        context: Context,
        sessionStore: SessionStore,
        sessionId: String,
        sessionDir: File,
        segments: List<File>,
        mediaScanWaiter: MediaScanWaiter = AndroidMediaScanWaiter(context),
        onProgress: (Float) -> Unit
    ): ExportResult {
        val displayName = "Rova_${TIMESTAMP_FORMAT.format(Date())}.mp4"
        return when (val tier = currentExportTier()) {
            ExportTier.TIER1_API29_PLUS -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    // currentExportTier()'s contract maps SDK_INT → tier;
                    // a TIER1 dispatch on a pre-Q device would only happen
                    // if SDK_INT regressed mid-process (impossible). Defer
                    // rather than crash.
                    ExportResult.UnknownSession(sessionId)
                } else {
                    exportTier1(
                        resolver = context.contentResolver,
                        sessionStore = sessionStore,
                        sessionId = sessionId,
                        segments = segments,
                        displayName = displayName,
                        onProgress = onProgress
                    )
                }
            }
            ExportTier.TIER2_API26_28, ExportTier.TIER3_API24_25 -> {
                exportPreQ(
                    sessionStore = sessionStore,
                    sessionId = sessionId,
                    sessionDir = sessionDir,
                    segments = segments,
                    displayName = displayName,
                    mediaScanWaiter = mediaScanWaiter,
                    tier = tier,
                    onProgress = onProgress
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun exportTier1(
        resolver: ContentResolver,
        sessionStore: SessionStore,
        sessionId: String,
        segments: List<File>,
        displayName: String,
        onProgress: (Float) -> Unit
    ): ExportResult {
        val exporter = Tier1Exporter(
            sessionStore = sessionStore,
            // The seam takes a sessionId for symmetry with the recovery
            // path; live insert uses a fresh displayName per session.
            insertPendingRow = { _ -> Tier1AndroidOps.insertPendingRow(resolver, displayName) },
            withPendingFd = { uri, mode, block ->
                Tier1AndroidOps.withPendingFd(resolver, uri, mode, block)
            },
            mux = { segs, fd -> VideoMerger.mergeSegmentsToFd(segs, fd, onProgress) },
            finalizePendingRow = { uri -> Tier1AndroidOps.finalizePendingRow(resolver, uri) },
            deletePendingRow = { uri -> Tier1AndroidOps.deletePendingRow(resolver, uri) },
            validatePending = { uri -> Tier1AndroidOps.validatePending(resolver, uri) }
        )
        return exporter.export(sessionId, segments)
    }

    private suspend fun exportPreQ(
        sessionStore: SessionStore,
        sessionId: String,
        sessionDir: File,
        segments: List<File>,
        displayName: String,
        mediaScanWaiter: MediaScanWaiter,
        tier: ExportTier,
        onProgress: (Float) -> Unit
    ): ExportResult {
        val privateTempFile = File(sessionDir, "$displayName.private")
        val publicTargetFile = File(
            File(
                @Suppress("DEPRECATION")
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "Rova"
            ),
            displayName
        )
        val muxLambda: suspend (List<File>, File) -> Unit = { segs, output ->
            VideoMerger.mergeSegments(segs, output, onProgress)
        }
        return when (tier) {
            ExportTier.TIER2_API26_28 -> {
                val exporter = Tier2Exporter(
                    sessionStore = sessionStore,
                    mediaScanWaiter = mediaScanWaiter,
                    mux = muxLambda
                )
                exporter.export(sessionId, segments, privateTempFile, publicTargetFile)
            }
            ExportTier.TIER3_API24_25 -> {
                val exporter = Tier3Exporter(
                    sessionStore = sessionStore,
                    mediaScanWaiter = mediaScanWaiter,
                    mux = muxLambda
                )
                exporter.export(sessionId, segments, privateTempFile, publicTargetFile)
            }
            ExportTier.TIER1_API29_PLUS -> error("exportPreQ called with TIER1")
        }
    }

    private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
}
