package com.aritr.rova.service.recovery

/**
 * Phase 1.5 recovery scan output. See [docs/adr/0005-recovery-scan.md].
 *
 * Recovery is **read-mostly**: the scanner classifies dead sessions, appends
 * validated contiguous orphan segment prefixes (only when `T != COMPLETED`),
 * and emits a per-session [SessionClassification]. It NEVER deletes; physical
 * deletion is owned by Phase 1.7 (post-export-recovery cleanup pass) or by
 * explicit user action through Phase 2 UI.
 *
 * Anomalies are **derived state** — recomputed each cold launch, never
 * persisted. They live in [RecoveryReport] for the duration of the process.
 */
data class RecoveryReport(
    val classifications: Map<String, SessionClassification>,
    val scanStartMillis: Long,
    val scanCompletedMillis: Long,
    val deferred: Boolean = false
)

/**
 * Outcome of classifying one session directory.
 *
 * @property sessionId the session identifier (directory name).
 * @property terminalAction the terminal write performed by the scan, if any.
 *   Null when `T` was already set (no terminal write) or session was
 *   live-owned/age-filtered (skipped entirely).
 * @property eligibility the discard eligibility flag emitted to consumers
 *   (Phase 1.7 cleanup, Phase 2 UI). Phase 1.5 NEVER acts on this flag.
 * @property anomalies the derived anomaly set for this session. Empty when
 *   manifest and disk agree. Anomalies force [DiscardEligibility.OFFER_DISCARD]
 *   except [Anomaly.MissingSegment] which can also force [DiscardEligibility.BLOCKED].
 * @property appendedSegmentFilenames the contiguous orphan-prefix filenames
 *   that the scan appended to `manifest.segments` via SessionStore.appendSegment.
 *   Empty when no append ran (T == COMPLETED, or no valid contiguous prefix).
 */
data class SessionClassification(
    val sessionId: String,
    val terminalAction: TerminalAction?,
    val eligibility: DiscardEligibility,
    val anomalies: List<Anomaly>,
    val appendedSegmentFilenames: List<String>
)

/**
 * Terminal write performed by the scan. The scanner ONLY ever writes
 * [Terminated.USER_STOPPED] or [Terminated.KILLED_FORCE_STOP] — never
 * [Terminated.KILLED_BY_SYSTEM] (RovaTickReceiver-owned) and never
 * [Terminated.COMPLETED] (RovaRecordingService.performMerge-owned).
 *
 * Use [com.aritr.rova.data.Terminated] for the actual enum; this type is a
 * narrowed view for diagnostic clarity.
 */
enum class TerminalAction {
    /** Wrote [com.aritr.rova.data.Terminated.USER_STOPPED] (manifest had `stopRequested=true`). */
    WROTE_USER_STOPPED,
    /** Wrote [com.aritr.rova.data.Terminated.KILLED_FORCE_STOP] (no surviving signal). */
    WROTE_KILLED_FORCE_STOP,
    /** Session was already terminal — no write performed. */
    ALREADY_TERMINAL,
    /** Skipped (live-owned by ServiceController or age-filtered). */
    SKIPPED,
    /**
     * Phase 1.4 (ADR 0006 B14 + B17 + B22): session has
     * `exportState in {MUXING, COPYING, FINALIZED} && terminated == null`.
     * Phase 1.7 export-recovery owns these; Phase 1.5 leaves them
     * untouched. NOT triggered for `exportState == FAILED` — that is a
     * Phase 1.5 input (the prior 1.7 pass already gave up).
     *
     * Until Phase 1.7 ships, sessions with this terminal action sit
     * `BLOCKED` in the recovery report and are hidden from the History
     * UI.
     */
    SKIPPED_EXPORT_PENDING
}

/**
 * Discard eligibility flag emitted by the scan. Phase 1.5 emits the flag;
 * deletion is performed only by Phase 1.7 post-export-recovery cleanup
 * (intersecting this flag with its own export-clean predicate) or by
 * explicit user action through Phase 2 UI.
 */
enum class DiscardEligibility {
    /**
     * `T` is terminal AND no surviving segments/orphans/anomalies.
     * Eligible for automatic deletion **after** Phase 1.7 confirms
     * export-clean state. Phase 1.5 does NOT delete.
     */
    AUTO_DISCARD_ELIGIBLE,
    /**
     * `T` is terminal but surviving artifacts (orphans, invalid segments,
     * unknown files, anomalies) remain. User must confirm deletion via
     * Phase 2 UI; UI must enumerate every surviving artifact.
     */
    OFFER_DISCARD,
    /**
     * `T == null` (classification didn't happen) OR session is owned by
     * a live ServiceController / age-filtered. Caller should retry on
     * the next scan rather than acting on this session.
     */
    BLOCKED
}

/**
 * Derived anomaly. Never persisted. Recomputed each scan.
 *
 * Anomalies are emitted by [RecoveryScanner.classify] and live in the
 * resulting [SessionClassification]. They surface to Phase 2 UI as
 * read-only evidence — the user reviews and chooses (merge survivors,
 * abandon and offer-discard, leave for later).
 */
sealed class Anomaly {
    /** Manifest claims segments whose files are absent from disk. */
    data class MissingSegment(val missingIndices: List<Int>) : Anomaly()

    /** Manifest segments whose files exist but fail [validateMediaFile]. */
    data class InvalidManifestSegment(val indices: List<Int>) : Anomaly()

    /**
     * Orphan segment-shaped files past the first gap or first invalid,
     * OR (when `T == COMPLETED`) every candidate orphan in the directory.
     */
    data class OrphanSegment(val indices: List<Int>) : Anomaly()

    /** Orphan segment-shaped files that fail [validateMediaFile]. */
    data class InvalidOrphan(val filenames: List<String>) : Anomaly()

    /** Indices duplicated across manifest+disk or two disk files. */
    data class DuplicateSegment(val indices: List<Int>) : Anomaly()

    /**
     * Files in the session dir matching neither `manifest.json[.tmp]`
     * nor the segment regex `^segment_(\d{4})\.mp4$` (legacy filenames,
     * partial merge outputs, leftover debug captures, export-tier orphans).
     */
    data class UnknownArtifact(val filenames: List<String>) : Anomaly()

    /**
     * Manifest [com.aritr.rova.data.SegmentRecord] entries whose `filename`
     * does not match the canonical segment regex `^segment_(\d{4})\.mp4$`.
     * The scan refuses to silently drop these records (ADR 0005 §"Missing /
     * Invalid Segment Handling": "A scan never rewrites manifest.segments
     * to match disk reality"). Any malformed record forces `OFFER_DISCARD`
     * so the user reviews before any cleanup.
     *
     * Distinct from [UnknownArtifact]: that type covers files on disk with
     * non-canonical names; this type covers manifest *records* with non-
     * canonical names. Both can fire for the same underlying file if the
     * file is also present on disk.
     */
    data class MalformedManifestRecord(val filenames: List<String>) : Anomaly()
}
