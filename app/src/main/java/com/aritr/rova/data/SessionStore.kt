package com.aritr.rova.data

import android.content.Context
import android.os.Build
import com.aritr.rova.utils.RovaLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Owns the on-disk layout under `getExternalFilesDir("videos")`:
 *
 * ```
 * videos/
 *   <sessionId>/
 *     manifest.json
 *     segment_0001.mp4
 *     segment_0002.mp4
 *     ...
 * ```
 *
 * **Durability contract:**
 * - Manifest writes are atomic-replace. API 26+ uses `Files.move(... ATOMIC_MOVE,
 *   REPLACE_EXISTING)`; API 24-25 falls back to `File.renameTo`, which on
 *   Android (POSIX `rename(2)` over ext4/F2FS) is atomic-replace on the same
 *   filesystem. The only window in which the manifest can be missing is the
 *   instant before the very first write — which is also the only window where
 *   no segments could possibly exist yet.
 * - [appendSegment] runs on a serial dispatcher (`Dispatchers.IO.limitedParallelism(1)`)
 *   so concurrent finalize callbacks queue in dispatch order, preserving
 *   sequence — no per-segment append can overtake an earlier one.
 *
 * See ROADMAP_v6.md §1.1 (C18) and ADR 0003 (storage-export-tiered).
 */
open class SessionStore internal constructor(rootDirArg: File) {

    /**
     * Test-friendly secondary constructor (Phase 1.5 / ADR 0005).
     *
     * Phase 1.4 (ADR 0006 B21) **deprecates this overload for production**.
     * Production callers MUST resolve the external root via
     * `RovaApp.videosRoot` and pass the resolved [File] through the
     * primary constructor. This overload remains only for
     * test-by-Robolectric paths and silently falls back to the legacy
     * `getExternalFilesDir(null)/videos` resolution. Production lint
     * `checkExternalRootShared` flags any non-`RovaApp` use.
     *
     * Throws if external storage is unavailable, matching the row 3
     * cleanup contract — never returns a SessionStore writing to a
     * relative path.
     */
    @Deprecated(
        "Use SessionStore(rootDir: File) with RovaApp.videosRoot per ADR 0006 B21",
        ReplaceWith("SessionStore(File(context.getExternalFilesDir(null)!!, \"videos\"))")
    )
    constructor(context: Context) : this(
        File(
            context.getExternalFilesDir(null)
                ?: error(
                    "SessionStore(Context): external storage unavailable. " +
                        "Use SessionStore(File) with RovaApp.videosRoot per ADR 0006 B21."
                ),
            "videos"
        )
    )

    private val rootDir: File = rootDirArg.also {
        if (!it.exists()) it.mkdirs()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val persistDispatcher = Dispatchers.IO.limitedParallelism(1)

    /**
     * Coroutines launched on [persistScope] are dispatched directly onto the
     * serial [persistDispatcher], so submission order == queue order. Going
     * via `Dispatchers.IO` first would let the work-body-start order race —
     * which is exactly the segment-N+1-overtakes-N bug we're avoiding.
     *
     * Lifecycle: cancelled by [close]. Keep alive as long as the owning
     * service's session is active.
     */
    private val persistScope = CoroutineScope(SupervisorJob() + persistDispatcher)

    fun rootDir(): File = rootDir

    fun sessionDir(sessionId: String): File = File(rootDir, sessionId)

    /**
     * Allocates a new session: generates a unique id (collision-checked against
     * existing dirs), picks tier per [Build.VERSION.SDK_INT], creates the dir,
     * persists the initial manifest. Synchronous — caller must invoke from a
     * non-Main dispatcher.
     */
    fun createSession(
        config: SessionConfig,
        audioMode: AudioMode = AudioMode.VIDEO_ONLY,
        hasUsableSafFolder: Boolean = false,
        // B5 / ADR-0025 — frozen at session start from RovaSettings.hideInVault.
        // Default false keeps every pre-B5 caller unchanged.
        vaultIntentAtStart: Boolean = false
    ): SessionManifest {
        val sessionId = generateUniqueSessionId()
        // Phase 1.6 (ROADMAP_v6 §1.6 / ADR 0003): tier from the shared
        // SDK→tier helper so the service preflight (which runs BEFORE
        // createSession) and this manifest commit cannot drift apart.
        // ADR-0024: hasUsableSafFolder=true freezes SAF_DESTINATION as the tier.
        val tier = currentExportTier(hasUsableSafFolder)
        val manifest = SessionManifest(
            sessionId = sessionId,
            startedAt = System.currentTimeMillis(),
            config = config,
            segments = emptyList(),
            exportTier = tier,
            audioMode = audioMode,
            vaultIntentAtStart = vaultIntentAtStart
        )
        val dir = sessionDir(sessionId)
        if (!dir.exists()) dir.mkdirs()
        writeManifestAtomic(dir, manifest)
        RovaLog.d { "SessionStore: created session $sessionId (tier=$tier, audioMode=$audioMode)" }
        return manifest
    }

    /**
     * Submit a finalized segment for persistence. Returns a [Deferred] that
     * completes once the manifest has been atomically updated.
     *
     * The async is launched on [persistScope], whose dispatcher is the serial
     * [persistDispatcher]. Each call queues directly on that dispatcher in
     * call order, so segment N+1 cannot append before segment N — even if N's
     * sha1 happens to be slower.
     *
     * The returned [Deferred] re-throws on `await()`; callers MUST await
     * (not join) so failures surface at the merge barrier. [join] silently
     * swallows exceptions and would let merge proceed with a missing record.
     */
    fun submitPersistFinalizedSegment(
        sessionId: String,
        segmentFile: File,
        filename: String,
        durationMs: Long
    ): Deferred<SegmentRecord> = persistScope.async {
        val sha1 = try {
            sha1Of(segmentFile)
        } catch (e: Exception) {
            RovaLog.w("submitPersistFinalizedSegment: sha1 failed for ${segmentFile.name}", e)
            ""
        }
        val record = SegmentRecord(
            filename = filename,
            durationMs = durationMs,
            sizeBytes = segmentFile.length(),
            sha1 = sha1
        )
        val dir = sessionDir(sessionId)
        val current = loadManifest(sessionId)
            ?: throw IllegalStateException("submitPersistFinalizedSegment: unknown session $sessionId")
        val updated = current.copy(segments = current.segments + record)
        writeManifestAtomic(dir, updated)
        record
    }

    /**
     * Pure manifest append. Used by recovery flows (Phase 1.5) and tests where
     * the caller has a pre-computed [SegmentRecord]. Runs on the same serial
     * dispatcher as [persistFinalizedSegment] so mixed callers cannot
     * interleave.
     */
    open suspend fun appendSegment(sessionId: String, record: SegmentRecord) =
        withContext(persistDispatcher) {
            val dir = sessionDir(sessionId)
            val current = loadManifest(sessionId)
                ?: throw IllegalStateException("appendSegment: unknown session $sessionId")
            val updated = current.copy(segments = current.segments + record)
            writeManifestAtomic(dir, updated)
        }

    /**
     * Phase 1.4 (ADR 0006 B2 / B9): atomic terminal-write. Writes
     * [Terminated] AND [StopReason] in a single manifest commit. Replaces
     * the Phase 1.2 two-arg form.
     *
     * Idempotent on first set — if [SessionManifest.terminated] is already
     * non-null, the existing pair wins (a later KILLED_BY_SYSTEM tick must
     * not overwrite an earlier COMPLETED; a duplicate user STOP must not
     * overwrite an earlier permission-revoked terminal). Returns
     * [MarkTerminatedResult.AlreadyTerminal] in that case, with the
     * existing pair so the loser thread can update its UI / log
     * accordingly.
     *
     * Atomicity: implemented via the existing manifest temp-file write +
     * `Files.move(ATOMIC_MOVE)` rename (API 26+). Single fsync, single
     * rename. Both fields land together or neither does.
     *
     * Retry: on `IOException` from temp-file write or rename, retries up
     * to 2 more times with a 50 ms backoff (3 attempts total). Other
     * `Throwable` types do not retry. On terminal failure, returns
     * [MarkTerminatedResult.Failed] with the cause and attempt count.
     * Caller MUST inspect the result; B9 caller contract: on `Failed`,
     * skip merge, surface a degraded-state notification, and defer to
     * cold-launch recovery.
     *
     * Runs on the serial [persistDispatcher] so the read-then-write is
     * race-free and cannot interleave with concurrent appendSegment /
     * submitPersistFinalizedSegment writes.
     *
     * Per ADR 0006 §"Migration table" the [stopReason] argument value is
     * driven by the meaning of the terminal write, not the writing class:
     * - [Terminated.KILLED_BY_SYSTEM] / [Terminated.KILLED_FORCE_STOP]
     *   writers pass [StopReason.NONE].
     * - [Terminated.COMPLETED] (merge-success) writer passes
     *   [StopReason.NONE].
     * - All [Terminated.USER_STOPPED] writers pass a non-`NONE` value.
     */
    open suspend fun markTerminated(
        sessionId: String,
        terminated: Terminated,
        stopReason: StopReason = StopReason.NONE
    ): MarkTerminatedResult = withContext(persistDispatcher) {
        val dir = sessionDir(sessionId)
        val current = loadManifest(sessionId)
            ?: return@withContext MarkTerminatedResult.Failed(
                cause = IllegalStateException("markTerminated: unknown session $sessionId"),
                attempts = 0
            )
        if (current.terminated != null) {
            RovaLog.d {
                "SessionStore: markTerminated($sessionId, $terminated, $stopReason) skipped" +
                    " — already ${current.terminated}/${current.stopReason}"
            }
            return@withContext MarkTerminatedResult.AlreadyTerminal(
                existingTerminated = current.terminated,
                existingStopReason = current.stopReason
            )
        }
        val updated = current.copy(
            terminated = terminated,
            terminatedAt = System.currentTimeMillis(),
            stopReason = stopReason
        )

        // 3-attempt retry on IOException (manifest temp-file write or
        // ATOMIC_MOVE rename). 50 ms backoff between attempts. Other
        // Throwable types do not retry.
        var attempt = 0
        var lastCause: Throwable? = null
        while (attempt < MARK_TERMINATED_MAX_ATTEMPTS) {
            attempt++
            try {
                writeManifestAtomic(dir, updated)
                RovaLog.d {
                    "SessionStore: markTerminated($sessionId, $terminated, $stopReason)" +
                        " written (attempt=$attempt)"
                }
                return@withContext MarkTerminatedResult.Wrote(terminated, stopReason)
            } catch (e: java.io.IOException) {
                lastCause = e
                RovaLog.w(
                    "SessionStore: markTerminated IOException (attempt $attempt/$MARK_TERMINATED_MAX_ATTEMPTS)" +
                        " for $sessionId", e
                )
                if (attempt < MARK_TERMINATED_MAX_ATTEMPTS) {
                    delay(MARK_TERMINATED_BACKOFF_MILLIS)
                }
            } catch (t: Throwable) {
                RovaLog.e(
                    "SessionStore: markTerminated non-retryable failure for $sessionId", t
                )
                return@withContext MarkTerminatedResult.Failed(t, attempt)
            }
        }
        MarkTerminatedResult.Failed(
            cause = lastCause ?: IllegalStateException("markTerminated retry exhausted"),
            attempts = attempt
        )
    }

    /**
     * Phase 1.7 commit-1 (ADR 0003 §"Recovery routing" + §"FD Mode
     * Amendment" partner). Shared single-atomic-write primitive for the
     * export-state setters below. Mirrors [markTerminated]'s discipline:
     *
     * - Runs on the serial [persistDispatcher] so read-then-write cannot
     *   interleave with concurrent appendSegment /
     *   submitPersistFinalizedSegment / setStopRequested writes.
     * - One logical manifest write per call. On `IOException` retries up
     *   to [MARK_TERMINATED_MAX_ATTEMPTS] times with
     *   [MARK_TERMINATED_BACKOFF_MILLIS] backoff (same budget as
     *   `markTerminated` — the failure modes and durability requirements
     *   are identical).
     * - Unknown session returns [ExportMutationResult.UnknownSession]
     *   without writing or throwing. The Phase 1.7 export pipeline
     *   needs the gate-style return contract because a recovered-and-
     *   discarded session can disappear mid-export; throwing would
     *   crash the foreground service.
     */
    private suspend fun mutateExport(
        label: String,
        sessionId: String,
        transform: (SessionManifest) -> SessionManifest
    ): ExportMutationResult = withContext(persistDispatcher) {
        val current = loadManifest(sessionId)
            ?: return@withContext ExportMutationResult.UnknownSession(sessionId)
        val updated = transform(current)
        var attempt = 0
        var lastCause: Throwable? = null
        while (attempt < MARK_TERMINATED_MAX_ATTEMPTS) {
            attempt++
            try {
                writeManifestAtomic(sessionDir(sessionId), updated)
                RovaLog.d { "SessionStore: $label($sessionId) written (attempt=$attempt)" }
                return@withContext ExportMutationResult.Wrote(updated)
            } catch (e: java.io.IOException) {
                lastCause = e
                RovaLog.w(
                    "SessionStore: $label IOException (attempt $attempt/$MARK_TERMINATED_MAX_ATTEMPTS) for $sessionId",
                    e
                )
                if (attempt < MARK_TERMINATED_MAX_ATTEMPTS) {
                    delay(MARK_TERMINATED_BACKOFF_MILLIS)
                }
            } catch (t: Throwable) {
                RovaLog.e("SessionStore: $label non-retryable failure for $sessionId", t)
                return@withContext ExportMutationResult.Failed(t, attempt)
            }
        }
        ExportMutationResult.Failed(
            cause = lastCause ?: IllegalStateException("$label retry exhausted"),
            attempts = attempt
        )
    }

    /**
     * Phase 1.7 (ADR 0003 §Recovery routing). Tier 1 commit point.
     * Writes the pending-row URI and transitions exportState to MUXING
     * in a single manifest write. Caller (Tier1Exporter) MUST invoke
     * this BEFORE `MediaMuxer.start()` so cold-launch recovery can
     * always find the manifest pointer that matches the inserted
     * `MediaStore` row.
     *
     * Effects: `pendingUri = pendingUri`, `exportState = MUXING`. No
     * other field touched.
     */
    open suspend fun setExportPending(
        sessionId: String,
        pendingUri: String
    ): ExportMutationResult = mutateExport("setExportPending", sessionId) { current ->
        current.copy(pendingUri = pendingUri, exportState = ExportState.MUXING)
    }

    /**
     * Phase 1.7 (ADR 0003 §Recovery routing). Tier 2/3 commit point A.
     * Writes both the private temp-file path and the eventual public
     * target path in a single manifest write; transitions exportState
     * to MUXING. Both pointers are set together so recovery's case
     * analysis (Tier 2/3 Cases A–D in ADR 0003 §Recovery routing) can
     * disambiguate disk states without inspecting partial field
     * combinations. Caller invokes BEFORE MediaMuxer construction.
     *
     * Effects: `privateTempPath = privateTempPath`,
     * `publicTargetPath = publicTargetPath`, `exportState = MUXING`.
     * No other field touched.
     */
    suspend fun setExportPrivateTarget(
        sessionId: String,
        privateTempPath: String,
        publicTargetPath: String
    ): ExportMutationResult = mutateExport("setExportPrivateTarget", sessionId) { current ->
        current.copy(
            privateTempPath = privateTempPath,
            publicTargetPath = publicTargetPath,
            exportState = ExportState.MUXING
        )
    }

    /**
     * Phase 1.7 (ADR 0003 §Recovery routing). Tier 2/3 transition.
     * Mux succeeded; the .part copy + rename + scanFile sequence is
     * about to begin. Pointer fields untouched — the temp file and
     * public target persist through the copy.
     *
     * Effects: `exportState = COPYING`. No other field touched.
     */
    suspend fun setExportCopying(sessionId: String): ExportMutationResult =
        mutateExport("setExportCopying", sessionId) { current ->
            current.copy(exportState = ExportState.COPYING)
        }

    /**
     * ADR-0024 §SAF route. Commit the private temp path before the SAF mux;
     * mirrors Tier 2/3 commit-point A ([setExportPrivateTarget]) for the SAF
     * copy path. Caller MUST invoke BEFORE `MediaMuxer.start()` so cold-launch
     * recovery can find the manifest pointer on the next boot.
     *
     * Effects: `privateTempPath = privateTempPath`, `exportState = MUXING`.
     * No other field touched.
     */
    suspend fun setExportSafPrivateTemp(
        sessionId: String,
        privateTempPath: String
    ): ExportMutationResult = mutateExport("setExportSafPrivateTemp", sessionId) { current ->
        current.copy(privateTempPath = privateTempPath, exportState = ExportState.MUXING)
    }

    /**
     * ADR-0024 §commit-before-stream. Commit the SAF document Uri BEFORE the
     * first byte is written to it. Transitions exportState to COPYING so the
     * recovery classifier can identify an in-progress SAF stream write after a
     * crash.
     *
     * Effects: `safTargetDocUri = docUri`, `exportState = COPYING`.
     * No other field touched.
     */
    suspend fun setExportSafTarget(
        sessionId: String,
        docUri: String
    ): ExportMutationResult = mutateExport("setExportSafTarget", sessionId) { current ->
        current.copy(safTargetDocUri = docUri, exportState = ExportState.COPYING)
    }

    /**
     * ADR-0024 §failure classification. Increment the transient-retry counter.
     * The counter escalates to a permanent failure classification at 3.
     *
     * Effects: `safTransientRetryCount += 1`. No other field touched.
     */
    suspend fun incrementSafTransientRetry(sessionId: String): ExportMutationResult =
        mutateExport("incrementSafTransientRetry", sessionId) { current ->
            current.copy(safTransientRetryCount = current.safTransientRetryCount + 1)
        }

    /**
     * ADR-0024 §SAF route per-side. Per-side analog of [setExportSafPrivateTemp]
     * for P+L sessions. Routes to `portraitPrivateTempPath` or
     * `landscapePrivateTempPath` based on [side]. Side-orthogonal.
     *
     * Effects: the matching side's `privateTempPath` field is set.
     * No other field touched.
     */
    suspend fun setExportSafPrivateTempForSide(
        sessionId: String,
        side: com.aritr.rova.service.dualrecord.VideoSide,
        privateTempPath: String
    ): ExportMutationResult = mutateExport("setExportSafPrivateTempForSide($side)", sessionId) { current ->
        val nextState = floorAdvanceToMuxing(current.exportState)
        when (side) {
            com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT ->
                current.copy(portraitPrivateTempPath = privateTempPath, exportState = nextState)
            com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE ->
                current.copy(landscapePrivateTempPath = privateTempPath, exportState = nextState)
        }
    }

    /**
     * ADR-0024 §SAF route per-side. Per-side analog of [setExportSafTarget]
     * for P+L sessions. Routes to `portraitSafTargetDocUri` or
     * `landscapeSafTargetDocUri` based on [side]. Side-orthogonal.
     *
     * Effects: the matching side's `safTargetDocUri` field is set.
     * No other field touched.
     */
    suspend fun setExportSafTargetForSide(
        sessionId: String,
        side: com.aritr.rova.service.dualrecord.VideoSide,
        docUri: String
    ): ExportMutationResult = mutateExport("setExportSafTargetForSide($side)", sessionId) { current ->
        when (side) {
            com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT ->
                current.copy(portraitSafTargetDocUri = docUri)
            com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE ->
                current.copy(landscapeSafTargetDocUri = docUri)
        }
    }

    /**
     * Phase 1.7 (ADR 0003 §Recovery routing). Tier 2/3 only.
     * `MediaScannerConnection.scanFile` callback fired; the gallery has
     * indexed the public artifact. Idempotent — a second call writes
     * the same `true` value. Pointer fields untouched; the caller's
     * subsequent [setExportFinalized] call clears `privateTempPath`
     * after the file is unlinked from disk.
     *
     * Effects: `mediaScanCompleted = true`. No other field touched.
     */
    suspend fun setMediaScanCompleted(sessionId: String): ExportMutationResult =
        mutateExport("setMediaScanCompleted", sessionId) { current ->
            current.copy(mediaScanCompleted = true)
        }

    /**
     * Phase 1.7 (ADR 0003 §Recovery routing). Final commit on the
     * export pipeline's success path. Transitions exportState to
     * FINALIZED.
     *
     * @param clearPrivateTempPath When `true` (Tier 2/3 happy path
     *   with scan callback fired), also clears `privateTempPath` —
     *   the caller MUST have already deleted the file from disk.
     *   When `false` (Tier 1, or Tier 2/3 with scan-callback timeout),
     *   the field is retained:
     *   - Tier 1 never sets `privateTempPath`, so the argument is a
     *     no-op there.
     *   - Tier 2/3 with scan-timeout retains `privateTempPath` so
     *     recovery's deferred-scan branch can re-fire `scanFile` on
     *     the next cold launch.
     *
     * Other pointer fields (`pendingUri`, `publicTargetPath`,
     * `mediaScanCompleted`) are retained: `pendingUri` for Tier 1
     * forensics, `publicTargetPath` as the artifact reference for
     * Phase 2 UI, `mediaScanCompleted` as the deferred-scan signal.
     *
     * Effects: `exportState = FINALIZED`; `privateTempPath = null` iff
     * `clearPrivateTempPath`. No other field touched.
     */
    open suspend fun setExportFinalized(
        sessionId: String,
        clearPrivateTempPath: Boolean
    ): ExportMutationResult = mutateExport("setExportFinalized", sessionId) { current ->
        if (clearPrivateTempPath) {
            current.copy(exportState = ExportState.FINALIZED, privateTempPath = null)
        } else {
            current.copy(exportState = ExportState.FINALIZED)
        }
    }

    /**
     * Phase 1.7 (ADR 0003 §Recovery routing). Failure path. Clears
     * every export pointer and resets `mediaScanCompleted`; transitions
     * exportState to FAILED. Caller is responsible for any pre-write
     * file unlinking (`ContentResolver.delete(pendingUri, ...)` on
     * Tier 1; private temp + .part file deletion on Tier 2/3).
     *
     * Once FAILED the session leaves Phase 1.7's automatic
     * responsibility set per ADR 0006 §"Ownership table" — future
     * retry is user-driven via Phase 2 UI only, never on the next
     * cold launch.
     *
     * Effects: `exportState = FAILED`, `pendingUri = null`,
     * `privateTempPath = null`, `publicTargetPath = null`,
     * `mediaScanCompleted = false`. No other field touched.
     */
    open suspend fun setExportFailed(sessionId: String): ExportMutationResult =
        mutateExport("setExportFailed", sessionId) { current ->
            current.copy(
                exportState = ExportState.FAILED,
                pendingUri = null,
                privateTempPath = null,
                publicTargetPath = null,
                mediaScanCompleted = false
            )
        }

    /**
     * Phase 6.1b T11 — per-side analog of [setExportPending] for P+L
     * sessions. Routes to `portraitPendingUri` or `landscapePendingUri`
     * based on [side]. Side-orthogonal: a portrait write never touches
     * landscape fields and vice versa.
     *
     * Phase 6.1b T18 final-review remediation: floor-advances shared
     * `exportState` from `NOT_STARTED` to `MUXING` on the first per-side
     * write, mirroring single-mode [setExportPending]. Without this,
     * `ExportRecoveryRunner.needsExportRecovery` (which gates on the
     * shared `exportState`) skipped crashed mid-export P+L sessions —
     * the per-side OQ-C lock means the Tier-1/2/3 exporters never call
     * the shared `setExportPending` / `setExportCopying` writers, so a
     * P+L session would otherwise remain at `NOT_STARTED` until T13's
     * shared `setExportFinalized`. The transition is single-direction
     * (only writes when `current == NOT_STARTED`); a later shared state
     * — COPYING / FINALIZED / FAILED — is preserved untouched.
     */
    open suspend fun setExportPendingForSide(
        sessionId: String,
        side: com.aritr.rova.service.dualrecord.VideoSide,
        uri: String
    ): ExportMutationResult = mutateExport("setExportPendingForSide($side)", sessionId) { current ->
        val nextState = floorAdvanceToMuxing(current.exportState)
        when (side) {
            com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT ->
                current.copy(portraitPendingUri = uri, exportState = nextState)
            com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE ->
                current.copy(landscapePendingUri = uri, exportState = nextState)
        }
    }

    /**
     * Phase 6.1b T11 — per-side analog of [setExportPrivateTarget] for
     * the Tier 2/3 path of a P+L session. Caller may invoke this
     * independently for each side (the two sides run separate mux
     * pipelines). Side-orthogonal.
     *
     * Phase 6.1b T18 final-review remediation: floor-advances shared
     * `exportState` from `NOT_STARTED` to `MUXING` on the first per-side
     * write — see [setExportPendingForSide] for the rationale (the
     * Tier 2/3 path enters via `setExportPrivateTargetForSide` instead
     * of `setExportPendingForSide`, so the floor-advance lives on both
     * entry points).
     */
    suspend fun setExportPrivateTargetForSide(
        sessionId: String,
        side: com.aritr.rova.service.dualrecord.VideoSide,
        privateTempPath: String
    ): ExportMutationResult = mutateExport("setExportPrivateTargetForSide($side)", sessionId) { current ->
        val nextState = floorAdvanceToMuxing(current.exportState)
        when (side) {
            com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT ->
                current.copy(portraitPrivateTempPath = privateTempPath, exportState = nextState)
            com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE ->
                current.copy(landscapePrivateTempPath = privateTempPath, exportState = nextState)
        }
    }

    /**
     * Phase 6.1b T18 final-review remediation — floor-only advance from
     * `NOT_STARTED` to `MUXING`. Returns the next state. Any later state
     * (`COPYING`, `FINALIZED`, `FAILED`) is preserved verbatim.
     */
    private fun floorAdvanceToMuxing(current: ExportState): ExportState =
        if (current == ExportState.NOT_STARTED) ExportState.MUXING else current

    /**
     * Phase 6.1b T11 — per-side analog of [setExportFinalized]. Writes
     * the public target path for [side]; optionally clears that side's
     * private temp path (Tier 2/3 happy path) or retains it (Tier 1, or
     * Tier 2/3 with scan-callback timeout — recovery's deferred-scan
     * branch needs it to re-fire `scanFile`). Side-orthogonal: the
     * other side's pointers are untouched.
     */
    open suspend fun setExportFinalizedForSide(
        sessionId: String,
        side: com.aritr.rova.service.dualrecord.VideoSide,
        publicTargetPath: String,
        clearPrivateTempPath: Boolean
    ): ExportMutationResult = mutateExport("setExportFinalizedForSide($side)", sessionId) { current ->
        when (side) {
            com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT -> current.copy(
                portraitPublicTargetPath = publicTargetPath,
                portraitPrivateTempPath = if (clearPrivateTempPath) null else current.portraitPrivateTempPath
            )
            com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE -> current.copy(
                landscapePublicTargetPath = publicTargetPath,
                landscapePrivateTempPath = if (clearPrivateTempPath) null else current.landscapePrivateTempPath
            )
        }
    }

    /**
     * Phase 6.1b T11 — per-side analog of [setMediaScanCompleted].
     * Idempotent — second call writes the same `true` value. The other
     * side's scan flag is untouched.
     */
    suspend fun setMediaScanCompletedForSide(
        sessionId: String,
        side: com.aritr.rova.service.dualrecord.VideoSide
    ): ExportMutationResult = mutateExport("setMediaScanCompletedForSide($side)", sessionId) { current ->
        when (side) {
            com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT ->
                current.copy(portraitMediaScanCompleted = true)
            com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE ->
                current.copy(landscapeMediaScanCompleted = true)
        }
    }

    /**
     * B5 / ADR-0025 — finalize a vault export. The merged artifact now lives
     * ONLY inside the private vault, so there is no public copy: this transition
     * sets [VaultState.VAULTED] + the vault file path, marks the export
     * [ExportState.FINALIZED], and clears every public pointer
     * (`pendingUri`, `publicTargetPath`, `safTargetDocUri`).
     *
     * Does NOT touch [SessionManifest.terminated] — vault finalize uses
     * `ExportState.FINALIZED` only and never writes `Terminated.COMPLETED`
     * (that write is reserved for `RovaRecordingService.performMerge` per
     * `checkCompletedWriteOnlyFromPerformMerge` / ADR 0006 B7).
     */
    suspend fun setVaultFinalized(
        sessionId: String,
        vaultFilePath: String
    ): ExportMutationResult = mutateExport("setVaultFinalized", sessionId) { current ->
        current.copy(
            vaultState = VaultState.VAULTED,
            vaultFilePath = vaultFilePath,
            exportState = ExportState.FINALIZED,
            pendingUri = null,
            publicTargetPath = null,
            safTargetDocUri = null,
            privateTempPath = null
        )
    }

    /**
     * B5 / ADR-0025 — per-side analog of [setVaultFinalized] for P+L sessions.
     * Routes to `portraitVaultFilePath` / `landscapeVaultFilePath` based on
     * [side] and clears that side's public pointers (`*PendingUri`,
     * `*PublicTargetPath`). Side-orthogonal: the other side's pointers are
     * untouched. The shared `vaultState` flips to [VaultState.VAULTED] — vault
     * membership is a whole-session property, not per-side.
     */
    suspend fun setVaultFinalizedForSide(
        sessionId: String,
        side: com.aritr.rova.service.dualrecord.VideoSide,
        vaultFilePath: String
    ): ExportMutationResult = mutateExport("setVaultFinalizedForSide($side)", sessionId) { current ->
        when (side) {
            com.aritr.rova.service.dualrecord.VideoSide.PORTRAIT -> current.copy(
                vaultState = VaultState.VAULTED,
                portraitVaultFilePath = vaultFilePath,
                portraitPendingUri = null,
                portraitPublicTargetPath = null
            )
            com.aritr.rova.service.dualrecord.VideoSide.LANDSCAPE -> current.copy(
                vaultState = VaultState.VAULTED,
                landscapeVaultFilePath = vaultFilePath,
                landscapePendingUri = null,
                landscapePublicTargetPath = null
            )
        }
    }

    /**
     * B5 / ADR-0025 — set an in-flight vault move state ([VaultState.VAULTING]
     * for move-in, [VaultState.UNVAULTING] for move-out) so cold-launch
     * recovery can resume an interrupted move. When [vaultFilePath] is `null`
     * the existing path is preserved; pass a non-null value only when the move
     * targets a new on-disk path.
     */
    suspend fun setVaultState(
        sessionId: String,
        state: VaultState,
        vaultFilePath: String? = null
    ): ExportMutationResult = mutateExport("setVaultState($state)", sessionId) { current ->
        current.copy(
            vaultState = state,
            vaultFilePath = vaultFilePath ?: current.vaultFilePath
        )
    }

    /**
     * B5 / ADR-0025 — move-out completion. The artifact left the vault and was
     * republished to public storage: reset [VaultState.PUBLIC], clear the vault
     * file path, and set the published pointers. Defaults to
     * [ExportState.FINALIZED] with no public pointers so the caller can also use
     * this as a plain "removed from vault" reset.
     */
    suspend fun setVaultMovedOut(
        sessionId: String,
        exportState: ExportState = ExportState.FINALIZED,
        pendingUri: String? = null,
        publicTargetPath: String? = null,
        safTargetDocUri: String? = null
    ): ExportMutationResult = mutateExport("setVaultMovedOut", sessionId) { current ->
        current.copy(
            vaultState = VaultState.PUBLIC,
            vaultFilePath = null,
            exportState = exportState,
            pendingUri = pendingUri,
            publicTargetPath = publicTargetPath,
            safTargetDocUri = safTargetDocUri,
            // The move-out completed: the in-flight commit-before-finalize
            // pointers have done their job; clear them so a stray re-run can't
            // re-trigger dedup (B5 / ADR-0025).
            pendingMoveOutUri = null,
            pendingMoveOutPath = null
        )
    }

    /**
     * B5 / ADR-0025 commit-before-finalize — Tier1 move-out: commit the freshly
     * inserted pending-row Uri to the manifest BEFORE `withPendingFd` /
     * `finalizePendingRow` makes the public copy irreversible. On a crash after
     * finalize but before [setVaultMovedOut], the next cold-launch resume reads
     * this pointer and deletes the orphaned row before re-publishing — so the
     * recording lands in exactly one public place. Mirrors [setExportSafTarget]
     * (ADR-0024 commit-before-stream) for the pending-row tier.
     */
    suspend fun setPendingMoveOutTier1(
        sessionId: String,
        pendingRowUri: String
    ): ExportMutationResult = mutateExport("setPendingMoveOutTier1", sessionId) { current ->
        current.copy(pendingMoveOutUri = pendingRowUri)
    }

    /**
     * B5 / ADR-0025 commit-before-finalize — pre-Q (Tier2/3) move-out: commit
     * the `<name>.mp4.part` path BEFORE the first byte is written, so a
     * crash-resume can delete the stale `.part` (or adopt an already-renamed
     * target) instead of allocating a second non-colliding name. See
     * [setPendingMoveOutTier1].
     */
    suspend fun setPendingMoveOutPreQ(
        sessionId: String,
        partPath: String
    ): ExportMutationResult = mutateExport("setPendingMoveOutPreQ", sessionId) { current ->
        current.copy(pendingMoveOutPath = partPath)
    }

    /**
     * B5 / ADR-0025 (move-IN completion, cosmetic follow-up) — flip to
     * [VaultState.VAULTED] AND clear the public pointers that
     * [deletePublic] just removed from storage. Like [setVaultState] it
     * PRESERVES [SessionManifest.vaultFilePath] (so a leftover public copy
     * stays recoverable), but unlike it also nulls `pendingUri` /
     * `publicTargetPath` / `safTargetDocUri` so the manifest no longer points
     * at a deleted artifact (which otherwise lingers and falsely protects a
     * dead MediaStore row from the orphan sweep). Replaces the bare
     * `setVaultState(VAULTED)` move-in terminal.
     */
    suspend fun setVaultStateVaultedAndClearPublic(
        sessionId: String
    ): ExportMutationResult = mutateExport("setVaultStateVaultedAndClearPublic", sessionId) { current ->
        current.copy(
            vaultState = VaultState.VAULTED,
            vaultFilePath = current.vaultFilePath,
            pendingUri = null,
            publicTargetPath = null,
            safTargetDocUri = null
        )
    }

    /**
     * Phase 1.3 cooperative-stop signal. Idempotent — once true, stays true.
     * Phase 1.2 only needs the field-shape; the writer is RovaStopReceiver in
     * Phase 1.3.
     */
    suspend fun setStopRequested(sessionId: String) =
        withContext(persistDispatcher) {
            val dir = sessionDir(sessionId)
            val current = loadManifest(sessionId)
                ?: throw IllegalStateException("setStopRequested: unknown session $sessionId")
            if (current.stopRequested) return@withContext
            writeManifestAtomic(dir, current.copy(stopRequested = true))
        }

    fun loadManifest(sessionId: String): SessionManifest? {
        val file = manifestFile(sessionId)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            SessionManifest.fromJson(JSONObject(file.readText()))
        } catch (e: Exception) {
            RovaLog.e("SessionStore: failed to parse manifest for $sessionId", e)
            null
        }
    }

    fun listSessionIds(): List<String> =
        rootDir.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    /**
     * Wired in Phase 2 by user-action ("Discard previous session"). Not called
     * automatically in Phase 1.1 — see C12.
     */
    fun discardSession(sessionId: String) {
        val dir = sessionDir(sessionId)
        if (!dir.exists()) return
        dir.deleteRecursively()
        RovaLog.d { "SessionStore: discarded session $sessionId" }
    }

    fun nextSegmentFilename(currentSegmentCount: Int): String =
        "segment_${"%04d".format(currentSegmentCount + 1)}.mp4"

    fun manifestFile(sessionId: String): File = File(sessionDir(sessionId), MANIFEST_NAME)

    /**
     * Cancels [persistScope] so any pending persist coroutines complete or
     * cancel. Call from the owning service's teardown path. Safe to invoke
     * multiple times.
     */
    fun close() {
        persistScope.cancel()
    }

    /**
     * Atomic-replace manifest write. Crash-safe: at every instant the on-disk
     * `manifest.json` is either the previous version or the new one — never
     * partial, never absent (after the first successful create).
     */
    private fun writeManifestAtomic(dir: File, manifest: SessionManifest) {
        val target = File(dir, MANIFEST_NAME)
        val tmp = File(dir, MANIFEST_NAME_TMP)
        tmp.writeText(manifest.toJson().toString(2))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // NIO Files.move with ATOMIC_MOVE + REPLACE_EXISTING gives the
            // strongest guarantee the platform supports.
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } else {
            // API 24-25: java.nio.file.Files is API 26+. Fall back to
            // File.renameTo, which on Android maps to POSIX rename(2) — atomic
            // and replace-existing on the same filesystem.
            if (!tmp.renameTo(target)) {
                // Last-ditch fallback: re-attempt with explicit delete only if
                // the rename failed. Leaves a small unsafe window, but only
                // reachable if the FS itself is misbehaving.
                target.delete()
                if (!tmp.renameTo(target)) {
                    // Direct copy-and-delete. Manifest may end up partial on
                    // crash here; logged loudly so triage finds it.
                    RovaLog.e("SessionStore: atomic rename failed; falling back to copy")
                    target.writeText(tmp.readText())
                    tmp.delete()
                }
            }
        }
    }

    /**
     * Generate a session id that is (a) human-readable and (b) collision-free
     * against existing session dirs. Format: `yyyyMMdd_HHmmss_<8-hex>` —
     * 32 bits of randomness on top of 1-second-resolution timestamp.
     * The dir-existence loop is belt-and-braces: even at one start per ms with
     * 32-bit random, collision probability is ~10^-7 per attempt; the loop
     * makes the actual probability of collision-without-detection zero.
     */
    private fun generateUniqueSessionId(): String {
        val tsFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        repeat(MAX_ID_ATTEMPTS) {
            val ts = tsFormat.format(Date())
            val rand = UUID.randomUUID().toString().replace("-", "").take(8)
            val candidate = "${ts}_$rand"
            if (!sessionDir(candidate).exists()) return candidate
        }
        // Pathological: 16 collisions in a row. Fall back to full UUID.
        return "uuid_${UUID.randomUUID()}"
    }

    companion object {
        const val MANIFEST_NAME = "manifest.json"
        const val MANIFEST_NAME_TMP = "manifest.json.tmp"
        private const val MAX_ID_ATTEMPTS = 16

        /** Phase 1.4 (ADR 0006 B9): retry budget for [markTerminated]. */
        const val MARK_TERMINATED_MAX_ATTEMPTS = 3
        const val MARK_TERMINATED_BACKOFF_MILLIS = 50L

        /**
         * Streaming SHA-1 of a file. 64 KB chunks. Use from Dispatchers.IO.
         */
        fun sha1Of(file: File): String {
            val md = MessageDigest.getInstance("SHA-1")
            file.inputStream().buffered(64 * 1024).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Phase 1.4 (ADR 0006 B2 / B9). Result of an atomic terminal-write.
 * Callers MUST inspect the result and act per the §"Caller contract":
 *
 * - [Wrote] — proceed: update notification, cancel alarms, run merge for
 *   `USER_STOPPED` flavors (merge happens AFTER the write per B3).
 * - [AlreadyTerminal] — proceed idempotently: existing terminal stays.
 *   The loser thread logs the suppressed write but continues to merge /
 *   stop via the same controller-coordinated path so we don't double-
 *   merge.
 * - [Failed] — DO NOT proceed with merge. Surface a degraded-state
 *   notification ("Stopped — recording state could not be saved. Will
 *   recover on next launch."), cancel alarms, log via
 *   [com.aritr.rova.utils.RovaCrashReporter], then `stopForeground +
 *   stopSelf`. Phase 1.5 cold-launch classifies the residual session.
 */
sealed class MarkTerminatedResult {
    data class Wrote(
        val terminated: Terminated,
        val stopReason: StopReason
    ) : MarkTerminatedResult()

    data class AlreadyTerminal(
        val existingTerminated: Terminated,
        val existingStopReason: StopReason
    ) : MarkTerminatedResult()

    data class Failed(
        val cause: Throwable,
        val attempts: Int
    ) : MarkTerminatedResult()
}

/**
 * Phase 1.7 commit-1 (ADR 0003 §"Recovery routing" partner). Result of
 * an export-state mutation. Mirrors [MarkTerminatedResult] in shape but
 * with separate variants for the two non-success cases — the Phase 1.7
 * recovery routines need to distinguish "session disappeared" from "I/O
 * failure" without unwrapping a `Throwable`.
 *
 * - [Wrote] — proceed: caller may rely on [Wrote.updated] being the new
 *   on-disk manifest state.
 * - [UnknownSession] — the [UnknownSession.sessionId] does not exist on
 *   disk (deleted by the cleanup pass, never created, or out-of-band
 *   removed). No write happened. Caller typically aborts the in-flight
 *   export and unwinds any external side-effects (`MediaStore` row,
 *   `.part` file, private temp file). Returning instead of throwing is
 *   load-bearing: the foreground-service export pipeline must not
 *   crash if Phase 1.7 cleanup discards a recovered session
 *   concurrently.
 * - [Failed] — `IOException` retry budget exhausted, or a non-retryable
 *   `Throwable`. The on-disk manifest is unchanged in the common case
 *   (atomic-rename is the only window in which partial state is
 *   reachable). Caller surfaces the failure and defers to the next
 *   cold-launch recovery pass.
 */
sealed class ExportMutationResult {
    data class Wrote(val updated: SessionManifest) : ExportMutationResult()

    data class UnknownSession(val sessionId: String) : ExportMutationResult()

    data class Failed(val cause: Throwable, val attempts: Int) : ExportMutationResult()
}
