package com.aritr.rova.utils

import java.io.File

/**
 * Pure per-side segment admission filter — the merge pipeline's realization
 * of the frozen invariant (spec `2026-07-08-dualshot-merge-validity-predicate`,
 * ADR-0005 §"Media Validity Rules"):
 *
 *   > "Use one definition of a valid recording throughout the recording
 *   > pipeline." A segment is admitted to merge iff the single validity
 *   > predicate accepts it. No stage may admit by a weaker test (e.g.
 *   > `length() > 0` alone).
 *
 * The predicate is **injected** ([partition]'s `isValid`); production always
 * passes `::validateMediaFile`. Each side is filtered **independently** — this
 * helper never compares the two sides, never truncates, pads, or assumes
 * symmetry. When both sides drop the same failing index and the counts end up
 * equal, that symmetry is an *outcome*, not a contract (see [divergenceMessage],
 * which only *reports* divergence, never enforces its absence).
 *
 * Pure and JVM-testable: no Android types, no framework calls — all media I/O
 * lives behind the injected predicate.
 */
object MergeSegmentFilter {

    /**
     * Why a segment was rejected, for truthful (non-silent) logging. The two
     * decode-level causes — no video track, and a track table with zero
     * readable video samples (the audio-only "frozen clip" stub) — both
     * surface as [INVALID_MEDIA]: [reasonFor] classifies from filesystem
     * facts only and never re-opens the extractor, so it cannot (and does not
     * need to) distinguish them. The predicate remains the sole authority on
     * validity; this enum only annotates a decision it already made.
     */
    enum class DropReason { MISSING, EMPTY, INVALID_MEDIA }

    data class Dropped(val file: File, val reason: DropReason)

    data class Result(val kept: List<File>, val dropped: List<Dropped>)

    /**
     * Partition [segments] into kept/dropped by the injected [isValid]
     * predicate. The predicate is the *only* validity decision; [reasonFor]
     * merely classifies each already-rejected file for logging.
     */
    fun partition(segments: List<File>, isValid: (File) -> Boolean): Result {
        val kept = ArrayList<File>(segments.size)
        val dropped = ArrayList<Dropped>()
        for (segment in segments) {
            if (isValid(segment)) kept.add(segment)
            else dropped.add(Dropped(segment, reasonFor(segment)))
        }
        return Result(kept, dropped)
    }

    /** Coarse, filesystem-only classification of an already-rejected file. */
    fun reasonFor(file: File): DropReason = when {
        !file.exists() -> DropReason.MISSING
        file.length() <= 0L -> DropReason.EMPTY
        else -> DropReason.INVALID_MEDIA
    }

    /**
     * A truthful per-side divergence line for `RovaLog.w`, or `null` when the
     * two sides kept equal counts. Reports divergence — does not enforce
     * symmetry (a legitimately-asymmetric session is degraded output, not an
     * error to correct here).
     */
    fun divergenceMessage(sessionId: String?, portraitKept: Int, landscapeKept: Int): String? =
        if (portraitKept == landscapeKept) null
        else "DualShot side divergence for session ${sessionId ?: "?"}: " +
            "portrait=$portraitKept landscape=$landscapeKept valid segment(s) — degraded output"
}
