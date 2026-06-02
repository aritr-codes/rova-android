package com.aritr.rova.data

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-session recording manifest. One manifest per session, written to
 * `videos/<sessionId>/manifest.json`. See ROADMAP_v6.md §1.1 (C18).
 *
 * Phase 1.1 populates: [sessionId], [startedAt], [config], [segments], [exportTier].
 * Phase 1.7 populates: [privateTempPath], [pendingUri], [publicTargetPath],
 * [mediaScanCompleted], [exportState].
 *
 * Schema is intentionally hand-serialized (no kotlinx.serialization plugin)
 * to keep dependencies minimal and survive field additions through field-by-
 * field reads with safe defaults.
 */
data class SessionManifest(
    val sessionId: String,
    val startedAt: Long,
    val config: SessionConfig,
    val segments: List<SegmentRecord>,
    val exportTier: ExportTier,
    val privateTempPath: String? = null,
    val pendingUri: String? = null,
    val publicTargetPath: String? = null,
    val mediaScanCompleted: Boolean = false,
    val exportState: ExportState = ExportState.NOT_STARTED,
    /**
     * Phase 1.2: terminal classification. `null` while the session is
     * potentially live (or recovery-required from a hard crash). Phase 1.5
     * recovery scan classifies orphans by inspecting [terminated] together
     * with on-disk segment count and ServiceController state.
     */
    val terminated: Terminated? = null,
    /** Wall-clock millis when [terminated] was set. `null` iff [terminated] is null. */
    val terminatedAt: Long? = null,
    /**
     * Phase 1.3 cooperative-stop intent flag. Receiver's second-line defense
     * checks `terminated != null || stopRequested` — a tick whose session
     * was asked to stop must still be a no-op even if no terminal record
     * has landed yet.
     */
    val stopRequested: Boolean = false,
    /**
     * Phase 1.4 (ADR 0006 B18) — locked at session start, immutable for the
     * session lifetime. Drives the FGS-type bitfield at `startForeground`
     * and the CameraX recorder configuration. Mid-session
     * `RECORD_AUDIO` revocation in a [AudioMode.VIDEO_AUDIO] session
     * forces termination because the FGS-type bitfield is immutable and
     * cannot legitimately hold `FOREGROUND_SERVICE_TYPE_MICROPHONE`
     * without permission. Legacy manifests (no field) default to
     * [AudioMode.VIDEO_ONLY] — the safer default for back-compat.
     */
    val audioMode: AudioMode = AudioMode.VIDEO_ONLY,
    /**
     * Phase 1.4 (ADR 0006 B2) — atomic sibling of [terminated]. Written in
     * the same manifest commit as [terminated]; never persisted on its own.
     * Phase 1.5 recovery scan ignores this field for classification (it is
     * descriptive, not load-bearing). UI consumes it for the user-facing
     * stop reason. Default [StopReason.NONE] applies to:
     * - manifests not yet stopped,
     * - merge-success [Terminated.COMPLETED],
     * - system-driven terminals ([Terminated.KILLED_BY_SYSTEM] /
     *   [Terminated.KILLED_FORCE_STOP]).
     */
    val stopReason: StopReason = StopReason.NONE,
    // Phase 6.1b T11 — per-side export-state pointers for P+L sessions.
    // Single-mode sessions leave all eight fields at their default
    // (null / false). The existing `privateTempPath` / `pendingUri` /
    // `publicTargetPath` / `mediaScanCompleted` quadruple remains the
    // single-mode source of truth — the per-side fields are additive,
    // never a replacement. See ADR 0003 §"Recovery routing" partner +
    // Phase 6.1b T11 plan.
    val portraitPrivateTempPath: String? = null,
    val portraitPendingUri: String? = null,
    val portraitPublicTargetPath: String? = null,
    val portraitMediaScanCompleted: Boolean = false,
    val landscapePrivateTempPath: String? = null,
    val landscapePendingUri: String? = null,
    val landscapePublicTargetPath: String? = null,
    val landscapeMediaScanCompleted: Boolean = false,
    // ADR-0024: SAF export-route fields (B4 SD-card track)
    val safTargetDocUri: String? = null,
    val portraitSafTargetDocUri: String? = null,
    val landscapeSafTargetDocUri: String? = null,
    val safTransientRetryCount: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("sessionId", sessionId)
        put("startedAt", startedAt)
        put("config", config.toJson())
        put("segments", JSONArray().also { arr -> segments.forEach { arr.put(it.toJson()) } })
        put("exportTier", exportTier.name)
        privateTempPath?.let { put("privateTempPath", it) }
        pendingUri?.let { put("pendingUri", it) }
        publicTargetPath?.let { put("publicTargetPath", it) }
        put("mediaScanCompleted", mediaScanCompleted)
        put("exportState", exportState.name)
        terminated?.let { put("terminated", it.name) }
        terminatedAt?.let { put("terminatedAt", it) }
        put("stopRequested", stopRequested)
        put("audioMode", audioMode.name)
        put("stopReason", stopReason.name)
        // Phase 6.1b T11 — emit per-side fields only when non-null /
        // non-default so v4 single-mode manifests keep their byte-shape
        // (no extra keys appear in the JSON for sessions that never
        // touched a per-side mutator).
        portraitPrivateTempPath?.let { put("portraitPrivateTempPath", it) }
        portraitPendingUri?.let { put("portraitPendingUri", it) }
        portraitPublicTargetPath?.let { put("portraitPublicTargetPath", it) }
        if (portraitMediaScanCompleted) put("portraitMediaScanCompleted", true)
        landscapePrivateTempPath?.let { put("landscapePrivateTempPath", it) }
        landscapePendingUri?.let { put("landscapePendingUri", it) }
        landscapePublicTargetPath?.let { put("landscapePublicTargetPath", it) }
        if (landscapeMediaScanCompleted) put("landscapeMediaScanCompleted", true)
        // ADR-0024: SAF export-route fields
        safTargetDocUri?.let { put("safTargetDocUri", it) }
        portraitSafTargetDocUri?.let { put("portraitSafTargetDocUri", it) }
        landscapeSafTargetDocUri?.let { put("landscapeSafTargetDocUri", it) }
        if (safTransientRetryCount > 0) put("safTransientRetryCount", safTransientRetryCount)
    }

    companion object {
        // v5 (Phase 6.1b): SegmentRecord.side optional discriminator for P+L
        // sessions (null = legacy/single-mode). v4 (Phase 6): added
        // SessionConfig.mode. v1/v2/v3 manifests read with safe default
        // ("Portrait"). v3 (Phase 1.4 / ADR 0006): added audioMode,
        // stopReason. v1/v2 manifests read with safe defaults (VIDEO_ONLY, NONE).
        const val SCHEMA_VERSION = 6   // 5->6: SAF export-route fields (ADR-0024)

        fun fromJson(json: JSONObject): SessionManifest = SessionManifest(
            sessionId = json.getString("sessionId"),
            startedAt = json.getLong("startedAt"),
            config = SessionConfig.fromJson(json.getJSONObject("config")),
            segments = json.getJSONArray("segments").let { arr ->
                List(arr.length()) { SegmentRecord.fromJson(arr.getJSONObject(it)) }
            },
            // Phase 1.7 commit-0 (ADR 0003 §"FD Mode Amendment" lint partner):
            // schema-2 manifests written by Phase 1.3 builds carry no
            // `exportTier`; missing-or-malformed values fall back to the
            // running build's tier so a downgraded read still routes
            // recovery on a code path the running build can execute.
            exportTier = json.optString("exportTier", "").ifEmpty { null }?.let {
                runCatching { ExportTier.valueOf(it) }.getOrNull()
            } ?: currentExportTier(),
            privateTempPath = json.optString("privateTempPath", "").ifEmpty { null },
            pendingUri = json.optString("pendingUri", "").ifEmpty { null },
            publicTargetPath = json.optString("publicTargetPath", "").ifEmpty { null },
            mediaScanCompleted = json.optBoolean("mediaScanCompleted", false),
            exportState = ExportState.valueOf(json.optString("exportState", ExportState.NOT_STARTED.name)),
            terminated = json.optString("terminated", "").ifEmpty { null }?.let {
                runCatching { Terminated.valueOf(it) }.getOrNull()
            },
            terminatedAt = if (json.has("terminatedAt")) json.optLong("terminatedAt") else null,
            stopRequested = json.optBoolean("stopRequested", false),
            audioMode = json.optString("audioMode", "").ifEmpty { null }?.let {
                runCatching { AudioMode.valueOf(it) }.getOrNull()
            } ?: AudioMode.VIDEO_ONLY,
            stopReason = json.optString("stopReason", "").ifEmpty { null }?.let {
                runCatching { StopReason.valueOf(it) }.getOrNull()
            } ?: StopReason.NONE,
            // Phase 6.1b T11 — read per-side fields with safe defaults.
            // v4 (and earlier) manifests have none of these keys, so
            // optString returns "" → null and optBoolean returns false.
            portraitPrivateTempPath = json.optString("portraitPrivateTempPath", "").ifEmpty { null },
            portraitPendingUri = json.optString("portraitPendingUri", "").ifEmpty { null },
            portraitPublicTargetPath = json.optString("portraitPublicTargetPath", "").ifEmpty { null },
            portraitMediaScanCompleted = json.optBoolean("portraitMediaScanCompleted", false),
            landscapePrivateTempPath = json.optString("landscapePrivateTempPath", "").ifEmpty { null },
            landscapePendingUri = json.optString("landscapePendingUri", "").ifEmpty { null },
            landscapePublicTargetPath = json.optString("landscapePublicTargetPath", "").ifEmpty { null },
            landscapeMediaScanCompleted = json.optBoolean("landscapeMediaScanCompleted", false),
            // ADR-0024: SAF export-route fields
            safTargetDocUri = json.optString("safTargetDocUri", "").ifEmpty { null },
            portraitSafTargetDocUri = json.optString("portraitSafTargetDocUri", "").ifEmpty { null },
            landscapeSafTargetDocUri = json.optString("landscapeSafTargetDocUri", "").ifEmpty { null },
            safTransientRetryCount = json.optInt("safTransientRetryCount", 0)
        )
    }
}

/**
 * Persisted session configuration. All four fields capture the user's
 * REQUESTED settings at session start; none of them are updated to
 * reflect what the device actually delivered.
 *
 * Specifically, [resolution] is the picker label the user chose
 * (`"SD" / "HD" / "FHD" / "4K"`) and is the input passed to
 * `QualitySelector.fromOrderedList(...)` with a fallback chain in
 * [com.aritr.rova.service.RovaRecordingService]. The CameraX recorder
 * may honor a lower quality on devices where the requested one is
 * unsupported — the manifest does NOT track that downgrade.
 *
 * The actual-output quality is derived on read from the produced
 * media file's real dimensions via
 * [com.aritr.rova.ui.screens.VideoMetadataUtils.extractMetadata],
 * which routes through [QualityLabels] so the History row label
 * matches the picker vocabulary. A divergence between the
 * Settings/Record picker (requested) and the History row (actual)
 * is the user-visible signal that a fallback occurred.
 */
data class SessionConfig(
    val durationSeconds: Int,
    val intervalMinutes: Int,
    val resolution: String,
    val loopCount: Int,
    val mode: String = "Portrait"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("durationSeconds", durationSeconds)
        put("intervalMinutes", intervalMinutes)
        put("resolution", resolution)
        put("loopCount", loopCount)
        put("mode", mode)
    }

    companion object {
        fun fromJson(json: JSONObject): SessionConfig = SessionConfig(
            durationSeconds = json.getInt("durationSeconds"),
            intervalMinutes = json.getInt("intervalMinutes"),
            resolution = json.getString("resolution"),
            loopCount = json.getInt("loopCount"),
            mode = json.optString("mode", "").ifEmpty { null }
                ?.takeIf { it == "Portrait" || it == "Landscape" || it == "PortraitLandscape" }
                ?: "Portrait"
        )
    }
}

data class SegmentRecord(
    val filename: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val sha1: String,
    val side: com.aritr.rova.service.dualrecord.VideoSide? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("filename", filename)
        put("durationMs", durationMs)
        put("sizeBytes", sizeBytes)
        put("sha1", sha1)
        side?.let { put("side", it.name) }
    }

    companion object {
        fun fromJson(json: JSONObject): SegmentRecord = SegmentRecord(
            filename = json.getString("filename"),
            durationMs = json.getLong("durationMs"),
            sizeBytes = json.getLong("sizeBytes"),
            sha1 = json.getString("sha1"),
            side = json.optString("side", "").ifEmpty { null }?.let {
                runCatching { com.aritr.rova.service.dualrecord.VideoSide.valueOf(it) }.getOrNull()
            }
        )
    }
}

enum class ExportTier {
    TIER1_API29_PLUS,
    TIER2_API26_28,
    TIER3_API24_25
}

/**
 * Phase 1.6 (ROADMAP_v6 §1.6 / ADR 0003) — single source of truth for the
 * `Build.VERSION.SDK_INT` → [ExportTier] map. Both
 * [SessionStore.createSession] (manifest commit) and the service-side
 * preflight (pre-`createSession`, in `onStartCommand`) call this so the
 * tier seen at preflight matches the tier persisted in the manifest.
 *
 * Tier is decided ONCE per session and frozen in the manifest.
 * Recovery on a downgraded build still treats the row by its **recorded**
 * tier — so the math must travel with the manifest, not the running build.
 */
fun currentExportTier(): ExportTier = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ExportTier.TIER1_API29_PLUS
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> ExportTier.TIER2_API26_28
    else -> ExportTier.TIER3_API24_25
}

/**
 * Phase 1.6 (ROADMAP_v6 §1.6) — peak-budget multiplier per tier.
 * Multiplier is applied to the **capture-bytes** estimate
 * (`segmentDuration × loops × bytesPerSec`).
 *
 * - **Tier 1** ⇒ `2`: capture + final mux into the pending row
 *   (one in-flight copy of the merged output).
 * - **Tier 2 / Tier 3** ⇒ `3`: capture + private merged + transient
 *   public copy (`<name>.mp4.part`) before `renameTo` makes it atomic.
 *
 * Does not include the per-segment safety buffer — preflight adds 50 MiB
 * separately, gate uses [com.aritr.rova.service.RovaRecordingService]'s
 * `FINALIZE_HEADROOM_MIB`.
 */
val ExportTier.peakBudgetMultiplier: Long
    get() = when (this) {
        ExportTier.TIER1_API29_PLUS -> 2L
        ExportTier.TIER2_API26_28, ExportTier.TIER3_API24_25 -> 3L
    }

enum class ExportState {
    NOT_STARTED,
    MUXING,
    COPYING,
    FINALIZED,
    FAILED
}

/**
 * Phase 1.2 session termination classification. Orthogonal to [ExportState] —
 * a session can be terminated (recording lifecycle ended) while export is
 * still in progress.
 *
 * - [USER_STOPPED]      Phase 1.3 will write this when stop is user-driven.
 * - [COMPLETED]         Phase 1.2 writes this on successful merge before
 *                       stopForeground/stopSelf.
 * - [KILLED_BY_SYSTEM]  Phase 1.2 writes this when a tick fires but
 *                       ServiceController has no controller registered
 *                       (process was killed between ticks).
 * - [KILLED_FORCE_STOP] Phase 1.5 recovery writes this when an orphan
 *                       session is detected with no terminated record and
 *                       no surviving manifest signal (force-stop or hard
 *                       crash mid-segment).
 */
enum class Terminated {
    USER_STOPPED,
    COMPLETED,
    KILLED_BY_SYSTEM,
    KILLED_FORCE_STOP,
    /**
     * Phase 4.3 — user chose to keep recovered segments as N separate
     * files instead of running the merge. Atomically written by the
     * `Keep as raw clips` / `Save segments only` CTAs via
     * `SessionStore.markTerminated(MULTI_SEGMENT_KEPT, StopReason.NONE)`.
     * Recovery card mapper hides it (no card emitted); Library row
     * enumeration is out-of-scope for this slice (see spec §4.11).
     */
    MULTI_SEGMENT_KEPT,
}

/**
 * Phase 1.4 (ADR 0006 B18). Decided ONCE at session start, persisted in
 * the manifest, immutable for the session lifetime.
 *
 * - [VIDEO_AUDIO] — `RECORD_AUDIO` granted at start. FGS started with
 *   `FOREGROUND_SERVICE_TYPE_CAMERA | FOREGROUND_SERVICE_TYPE_MICROPHONE`.
 *   Mid-session mic revocation forces termination (B18).
 * - [VIDEO_ONLY] — `RECORD_AUDIO` denied at start (or user opted out via
 *   the "record without audio?" prompt). FGS started with
 *   `FOREGROUND_SERVICE_TYPE_CAMERA` only. Mid-session mic permission
 *   changes are no-ops.
 *
 * Legacy default: [VIDEO_ONLY] (safer; never claims a microphone
 * capability the manifest can't verify the FGS actually held).
 */
enum class AudioMode {
    VIDEO_AUDIO,
    VIDEO_ONLY
}

/**
 * Phase 1.4 (ADR 0006 B2). Atomic sibling of [Terminated]. Written by
 * [com.aritr.rova.data.SessionStore.markTerminated] in the same manifest
 * commit as the terminal value; never persisted on its own.
 *
 * Phase 1.5 recovery scan does NOT read [StopReason] for classification —
 * it is descriptive, not load-bearing for the recovery decision matrix.
 *
 * Per the ADR 0006 §"Migration table":
 * - [USER_STOPPED] from `RovaStopReceiver` (both branches), `RecoveryScanner`'s
 *   `stopRequested=true` branch, and `RovaRecordingService.stopPeriodicRecordingAndMerge`
 *   user-stop path → [USER].
 * - [USER_STOPPED] from `RovaRecordingService` permission gate → [PERMISSION_REVOKED].
 * - [USER_STOPPED] from `RovaRecordingService` low-storage gate → [LOW_STORAGE].
 * - [USER_STOPPED] from `RovaRecordingService` post-manifest init failure
 *   (controller-register collision; camera bind error) → [INIT_FAILED].
 * - [USER_STOPPED] from `RovaRecordingService` thermal gate → [THERMAL].
 * - [Terminated.COMPLETED] (merge-success) → [NONE].
 * - [Terminated.KILLED_BY_SYSTEM] / [Terminated.KILLED_FORCE_STOP] → [NONE].
 */
enum class StopReason {
    USER,
    LOW_STORAGE,
    PERMISSION_REVOKED,
    INIT_FAILED,
    /**
     * Phase 4 Slice 3 — service Layer-4 thermal gate fires when
     * `ThermalStatusSignal.state.value` is at or above
     * `ThermalStatus.CRITICAL`. The eager-write contract writes
     * `Terminated.USER_STOPPED` + this reason atomically (B3).
     * See ADR-0016.
     */
    THERMAL,
    NONE
}
