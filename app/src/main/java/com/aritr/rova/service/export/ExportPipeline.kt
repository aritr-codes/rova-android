package com.aritr.rova.service.export

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.annotation.RequiresApi
import com.aritr.rova.data.ExportTier
import com.aritr.rova.data.SessionStore
import com.aritr.rova.data.currentExportTier
import com.aritr.rova.utils.VideoMerger
import java.io.File
import java.io.IOException
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
        side: com.aritr.rova.service.dualrecord.VideoSide? = null,
        // ADR-0024 — the session's frozen export route. When null (every
        // pre-B4 caller) the SDK tier is computed live. RovaRecordingService
        // passes `manifest.exportTier` so a SAF-frozen session dispatches to
        // [exportSaf] instead of the SDK-derived tier.
        frozenTier: ExportTier? = null,
        onProgress: (Float) -> Unit
    ): ExportResult {
        // Phase 1.7 commit-7 (NO-GO patch round 1, blocker 1): include
        // milliseconds in the timestamp so two stops within a second
        // do not produce the same name. Tier 2/3 add a second probe-
        // and-suffix layer in [exportPreQ]; Tier 1 relies on
        // MediaStore's automatic " (N)" disambiguation on insert.
        // Phase 6.1b T12: append `_portrait` / `_landscape` to the
        // displayName when `side != null` so the two sides of a single
        // P+L stop produce distinguishable artifacts.
        val baseName = "Rova_${TIMESTAMP_FORMAT.format(Date())}"
        val suffix = when (side) {
            com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT -> "_portrait"
            com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE -> "_landscape"
            null -> ""
        }
        val displayName = "${baseName}${suffix}.mp4"
        return when (val tier = frozenTier ?: currentExportTier()) {
            ExportTier.SAF_DESTINATION -> exportSaf(
                context = context,
                sessionStore = sessionStore,
                sessionId = sessionId,
                sessionDir = sessionDir,
                segments = segments,
                displayName = displayName,
                side = side,
                onProgress = onProgress
            )
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
                        side = side,
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
                    side = side,
                    onProgress = onProgress
                )
            }
        }
    }

    /**
     * ADR-0024 — SAF export route. A destination variant of the pre-Q path:
     * mux to a local private temp then publish into the user-chosen SAF
     * document. The muxer NEVER targets a SAF descriptor — `mux` writes the
     * local temp; [SafExporter] copies it into the doc. `VideoMerger.mergeSegments`
     * is called inside `service/export/` here, satisfying the
     * `checkExportPipelineSingleEntry` invariant 2.
     */
    private suspend fun exportSaf(
        context: Context,
        sessionStore: SessionStore,
        sessionId: String,
        sessionDir: File,
        segments: List<File>,
        displayName: String,
        side: com.aritr.rova.service.dualrecord.VideoSide?,
        onProgress: (Float) -> Unit
    ): ExportResult {
        val settings = com.aritr.rova.data.RovaSettings(context)
        val treeUri = settings.saveLocationTreeUri
            ?: return ExportResult.SafFolderUnavailable(null)   // setting cleared mid-flight
        val privateTemp = File(sessionDir, "$displayName.private")
        val exporter = SafExporter(
            displayName = displayName,
            privateTempFile = privateTemp,
            setSafPrivateTemp = { p ->
                if (side == null) sessionStore.setExportSafPrivateTemp(sessionId, p)
                else sessionStore.setExportSafPrivateTempForSide(sessionId, side, p)
            },
            setSafTarget = { d ->
                if (side == null) sessionStore.setExportSafTarget(sessionId, d)
                else sessionStore.setExportSafTargetForSide(sessionId, side, d)
            },
            setFinalizedClear = {
                if (side == null) sessionStore.setExportFinalized(sessionId, clearPrivateTempPath = true)
                else sessionStore.setExportFinalizedForSide(sessionId, side, publicTargetPath = "", clearPrivateTempPath = true)
            },
            setFailed = { sessionStore.setExportFailed(sessionId) },
            incrementRetry = { sessionStore.incrementSafTransientRetry(sessionId) },
            currentRetryCount = { sessionStore.loadManifest(sessionId)?.safTransientRetryCount ?: 0 },
            mux = { segs, out -> VideoMerger.mergeSegments(segs, out, onProgress) },
            createDocument = { name -> SafAndroidOps.createDocument(context, treeUri, name) },
            displayNameOf = { d -> SafAndroidOps.displayNameOf(context, d) },
            copyFileToDocument = { src, d -> SafAndroidOps.copyFileToDocument(context, src, d) },
            validateDocument = { d -> SafAndroidOps.validateDocument(context, d) },
            deleteDocument = { d -> SafAndroidOps.deleteDocument(context, d) },
            isPermissionHeld = { SafAndroidOps.isPersistedPermissionHeld(context, treeUri) }
        )
        return exporter.export(sessionId, segments)
    }

    /**
     * Phase 4.3 — recovery merge entry. Same tier dispatch as [export] but
     * runs an eager storage pre-flight before opening any muxer; returns
     * [ExportResult.InsufficientStorage] if the destination cannot hold
     * the merged file. Caller maps this to `WarningId.CANT_MERGE` via
     * [RecoveryMergeOutcomeSignal].
     *
     * The 5% headroom over `sum(segment.length())` accounts for muxer
     * overhead (track tables, moov atom, padding).
     */
    suspend fun exportRecovered(
        context: Context,
        sessionStore: SessionStore,
        sessionId: String,
        sessionDir: File,
        segments: List<File>,
        mediaScanWaiter: MediaScanWaiter = AndroidMediaScanWaiter(context),
        onProgress: (Float) -> Unit,
    ): ExportResult {
        val required = (segments.sumOf { it.length() } * 1.05).toLong()
        val available = availableSpaceFor(context, sessionDir)
        if (available < required) {
            return ExportResult.InsufficientStorage(requiredBytes = required, availableBytes = available)
        }
        // Recovery merge reuses live tier dispatch verbatim once pre-flight clears.
        // `side = null` because recovery merge is never a P+L per-side resume.
        return export(
            context = context,
            sessionStore = sessionStore,
            sessionId = sessionId,
            sessionDir = sessionDir,
            segments = segments,
            mediaScanWaiter = mediaScanWaiter,
            side = null,
            onProgress = onProgress,
        )
    }

    /**
     * Phase 4.3 — storage probe for `exportRecovered` pre-flight. Prefers
     * [StorageManager.getAllocatableBytes] on API 26+ (reports bytes the
     * OS could free by purging cache content if needed — more accurate
     * than raw free space), falls back to [File.usableSpace] on API 24/25
     * and on any failure of the allocatable probe. Returns 0 only if the
     * fallback itself reports 0 — both paths guarantee a non-negative
     * value.
     */
    private fun availableSpaceFor(context: Context, sessionDir: File): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                if (sm != null) {
                    val uuid = sm.getUuidForPath(sessionDir)
                    return sm.getAllocatableBytes(uuid)
                }
            } catch (_: IOException) {
                // fall through to usableSpace
            }
        }
        return sessionDir.usableSpace
    }

    /**
     * Phase 4.3 — pure test seam. Same shape as [exportRecovered] but with
     * the storage probe and merge step injected as lambdas so the pre-flight
     * branch can be verified without `Context` / `SessionStore` / `MediaMuxer`
     * dependencies.
     */
    internal suspend fun exportRecoveredForTest(
        segments: List<File>,
        availableBytesProvider: () -> Long,
        performMerge: suspend (List<File>, (Float) -> Unit) -> ExportResult,
        onProgress: (Float) -> Unit,
    ): ExportResult {
        val required = (segments.sumOf { it.length() } * 1.05).toLong()
        val available = availableBytesProvider()
        if (available < required) {
            return ExportResult.InsufficientStorage(requiredBytes = required, availableBytes = available)
        }
        return performMerge(segments, onProgress)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun exportTier1(
        resolver: ContentResolver,
        sessionStore: SessionStore,
        sessionId: String,
        segments: List<File>,
        displayName: String,
        side: com.aritr.rova.service.dualrecord.VideoSide?,
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
        return exporter.export(sessionId, segments, side)
    }

    private suspend fun exportPreQ(
        sessionStore: SessionStore,
        sessionId: String,
        sessionDir: File,
        segments: List<File>,
        displayName: String,
        mediaScanWaiter: MediaScanWaiter,
        tier: ExportTier,
        side: com.aritr.rova.service.dualrecord.VideoSide?,
        onProgress: (Float) -> Unit
    ): ExportResult {
        @Suppress("DEPRECATION")
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "Rova"
        )
        // Phase 1.7 commit-7 (NO-GO patch round 1, blocker 1): probe
        // public-target / .part / private-temp triple for collision.
        // Suffix `_2`, `_3`, ... until all three slots are free. This
        // is defense in depth on top of the millisecond timestamp in
        // displayName — back-to-back exports, clock skew across recovery
        // resumes, or a stranded .part from a prior crash all collapse
        // to the same probe loop.
        val (publicTargetFile, privateTempFile) =
            allocateNonCollidingTarget(publicDir, sessionDir, displayName)
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
                exporter.export(sessionId, segments, privateTempFile, publicTargetFile, side)
            }
            ExportTier.TIER3_API24_25 -> {
                val exporter = Tier3Exporter(
                    sessionStore = sessionStore,
                    mediaScanWaiter = mediaScanWaiter,
                    mux = muxLambda
                )
                exporter.export(sessionId, segments, privateTempFile, publicTargetFile, side)
            }
            ExportTier.TIER1_API29_PLUS -> error("exportPreQ called with TIER1")
            // SAF is dispatched by export() → exportSaf; it never reaches preQ.
            ExportTier.SAF_DESTINATION -> error("exportPreQ called with SAF_DESTINATION")
        }
    }

    /**
     * Phase 1.7 commit-7 (NO-GO patch round 1, blocker 1) — Tier 2/3
     * collision-avoidance probe. Returns a (publicTarget, privateTemp)
     * pair such that NEITHER `publicTarget` NOR `<publicTarget>.part`
     * NOR `privateTemp` exists at the time of the call. The caller's
     * mux step lands the output in `privateTemp`; the publish atom
     * renames `<publicTarget>.part → publicTarget`.
     *
     * Timestamps in [displayName] include milliseconds, so collisions
     * here are rare — but a previous-crash leftover `.part` or a
     * resumed/concurrent export can still occupy a slot. The probe
     * removes the only failure mode that would silently lose user
     * data: pre-existing `publicTarget` overwritten by `renameTo`, or
     * pre-existing `publicTarget` deleted by the post-failure cleanup
     * in [PreQExportCore.cleanupOnFailure].
     *
     * `publicDir.mkdirs()` runs unconditionally so the rename target
     * directory exists when the publish atom fires. Suffix exhaustion
     * ([SUFFIX_LIMIT]) is treated as `IllegalStateException` because
     * 9999 distinct collisions in the same millisecond is a system
     * pathology, not a recoverable export error.
     */
    private fun allocateNonCollidingTarget(
        publicDir: File,
        sessionDir: File,
        displayName: String
    ): Pair<File, File> {
        publicDir.mkdirs()
        val dot = displayName.lastIndexOf('.')
        val stem = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""

        var i = 1
        while (i <= SUFFIX_LIMIT) {
            val name = if (i == 1) displayName else "${stem}_$i$ext"
            val publicTarget = File(publicDir, name)
            val partFile = File(publicDir, "$name.part")
            val privateTemp = File(sessionDir, "$name.private")
            if (!publicTarget.exists() && !partFile.exists() && !privateTemp.exists()) {
                return publicTarget to privateTemp
            }
            i++
        }
        throw IllegalStateException(
            "ExportPipeline: collision-suffix space exhausted for $displayName " +
                "in ${publicDir.absolutePath} after $SUFFIX_LIMIT attempts"
        )
    }

    private const val SUFFIX_LIMIT = 9999

    // SimpleDateFormat is NOT thread-safe across coroutines; the format
    // call below runs synchronously inside `export()` which is invoked
    // sequentially from `RovaRecordingService.performMerge` (single
    // call site enforced by `checkExportPipelineSingleEntry`), so a
    // single instance is safe for this use. Millisecond precision
    // (`SSS`) reduces same-instant collisions; the probe loop above is
    // defense in depth.
    private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
}
