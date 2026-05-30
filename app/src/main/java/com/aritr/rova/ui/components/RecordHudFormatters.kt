package com.aritr.rova.ui.components

/**
 * Slice 3 — pure presentation helpers for the active Record HUD.
 *
 * Sentinel handling:
 *   - `totalLoops < 0` ⇒ Continuous session. Loop position renders
 *     "Loop X" (no `/ N`); remaining-loops phrase renders the
 *     continuous-safe copy "Records until you stop". The internal
 *     `-1` sentinel never reaches user-facing UI.
 *
 * Time formatting:
 *   - Sub-hour values render as `MM:SS`. One-hour-and-over render as
 *     `H:MM:SS` so the HUD does not silently roll over a 60-minute
 *     session into "00:00".
 *
 * All functions are pure and JVM-testable. No Android dependency.
 */
object RecordHudFormatters {

    fun formatMmSs(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0L)
        val hours = s / 3600
        val minutes = (s % 3600) / 60
        val seconds = s % 60
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    fun formatLoopPosition(currentLoop: Int, totalLoops: Int): String {
        val safeCurrent = currentLoop.coerceAtLeast(0)
        return if (totalLoops < 0) {
            "Loop $safeCurrent"
        } else {
            "Loop $safeCurrent / $totalLoops"
        }
    }

    fun formatLoopsRemaining(currentLoop: Int, totalLoops: Int): String {
        if (totalLoops < 0) return "Records until you stop"
        val safeTotal = totalLoops.coerceAtLeast(0)
        val remaining = (safeTotal - currentLoop).coerceAtLeast(0)
        return when {
            safeTotal == 0 -> "Last clip in progress"
            remaining == 0 -> "Last clip in progress"
            remaining == 1 -> "1 of $safeTotal loops remaining"
            else -> "$remaining of $safeTotal loops remaining"
        }
    }

    fun formatNextClipDurationLabel(clipSeconds: Int): String {
        val s = clipSeconds.coerceAtLeast(0)
        return when {
            s == 0 -> "Next clip will run momentarily"
            s < 60 -> "Next clip will run for $s s"
            s == 60 -> "Next clip will run for 1 m"
            s % 60 == 0 -> "Next clip will run for ${s / 60} m"
            else -> "Next clip will run for $s s"
        }
    }

    /**
     * Status line under the session timer for the recording variant.
     * Combines the user-facing quality token with a short flash-state
     * descriptor. Never exposes raw integer flash codes.
     */
    fun formatRecordingMeta(quality: String, flashLabel: String): String =
        "$quality · $flashLabel"

    fun formatFlashLabel(flashMode: Int): String = when (flashMode) {
        FLASH_MODE_AUTO -> "Flash auto"
        FLASH_MODE_ON -> "Flash on"
        else -> "Flash off"
    }

    /**
     * Talkback-friendly announcement for the session-elapsed timer.
     * Rendered into a polite live region only on minute boundaries
     * (per UI_ROADMAP §"Slice 3 special requirements") — the caller
     * is responsible for the throttling; this helper only formats.
     */
    fun formatElapsedAnnouncement(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0L)
        val hours = s / 3600
        val minutes = (s % 3600) / 60
        val hourWord = if (hours == 1L) "hour" else "hours"
        val minuteWord = if (minutes == 1L) "minute" else "minutes"
        return when {
            hours > 0L && minutes > 0L ->
                "Session elapsed $hours $hourWord $minutes $minuteWord"
            hours > 0L -> "Session elapsed $hours $hourWord"
            minutes == 0L -> "Session just started"
            else -> "Session elapsed $minutes $minuteWord"
        }
    }

    /**
     * Clip-progress numerator/denominator such as `07 / 30 s` from the
     * mockup. Both digits are zero-padded to two when under 100 s so
     * the field width stays stable while a clip ticks 0..N seconds.
     */
    fun formatClipProgressNumbers(elapsedSeconds: Int, totalSeconds: Int): String {
        val safeElapsed = elapsedSeconds.coerceIn(0, totalSeconds.coerceAtLeast(0))
        val pad = totalSeconds.coerceAtLeast(0) >= 10
        val unit = if (totalSeconds >= 60) "s" else "s"
        return if (pad) {
            "%02d / %02d %s".format(safeElapsed, totalSeconds.coerceAtLeast(0), unit)
        } else {
            "%d / %d %s".format(safeElapsed, totalSeconds.coerceAtLeast(0), unit)
        }
    }

    /**
     * Fractional clip progress in [0, 1]. Returns 0 for non-positive
     * total to keep the progress bar from rendering an indeterminate
     * sentinel.
     */
    fun computeClipProgress(elapsedSeconds: Int, totalSeconds: Int): Float {
        if (totalSeconds <= 0) return 0f
        val ratio = elapsedSeconds.toFloat() / totalSeconds.toFloat()
        return ratio.coerceIn(0f, 1f)
    }

    /** Mirrors [com.aritr.rova.service.RovaRecordingService] flash codes. */
    private const val FLASH_MODE_OFF = 0
    private const val FLASH_MODE_ON = 1
    private const val FLASH_MODE_AUTO = 2

    // ─── Phase 2.4 — merge HUD copy ───────────────────────────────

    /**
     * Numerator/denominator for the in-HUD merge band, e.g. "3 of 6".
     * `currentSegment` is clamped into `[0, totalSegments]` to absorb
     * the brief overshoot when the export pipeline emits a 1.0
     * progress tick before the segment count snapshot has been
     * trimmed.
     */
    fun formatMergeProgressNumbers(currentSegment: Int, totalSegments: Int): String {
        val total = totalSegments.coerceAtLeast(0)
        val current = currentSegment.coerceIn(0, total)
        return "$current of $total"
    }

    /**
     * Sub-line copy for the merge band. Returns `null` when there is
     * nothing useful to say — the band hides the line in that case
     * rather than rendering a hollow placeholder.
     *
     * The mockup shows "About 20 seconds remaining" mid-merge; the
     * service exposes only fractional progress, not a wall-clock
     * estimate, so we render coarse phases instead of fabricating an
     * ETA.
     */
    fun formatMergeRemaining(progress: Float): String? {
        val p = progress.coerceIn(0f, 1f)
        return when {
            p < 0.05f -> "Starting merge…"
            p >= 0.85f -> "Almost done…"
            else -> null
        }
    }

    /**
     * TalkBack-friendly content description for the merge band. The
     * caller publishes this as a static `contentDescription` (not a
     * live region) so screen readers do not chant on every fractional
     * progress tick — the description still updates whenever the
     * `currentSegment` rolls forward, which is the meaningful
     * accessibility boundary.
     */
    fun formatMergeAnnouncement(currentSegment: Int, totalSegments: Int): String {
        val total = totalSegments.coerceAtLeast(0)
        val current = currentSegment.coerceIn(0, total)
        if (total <= 0) return "Preparing to merge"
        if (current <= 0) return "Preparing to merge $total clips"
        return "Merging clip $current of $total"
    }

    /**
     * TalkBack announcement for [com.aritr.rova.ui.components.SessionStatusCard]
     * (WCAG 2.2 AA SC 4.1.3, ADR-0020, SHAR-09). Published as the card's
     * content description in a polite live region — it carries the recording
     * state + loop position (not the fractional progress bar value), so it
     * re-announces on a loop roll, not on every progress frame.
     */
    fun formatSessionStatusAnnouncement(
        isRecording: Boolean,
        currentLoop: Int,
        totalLoops: Int,
    ): String {
        val state = if (isRecording) "Recording" else "Queued"
        val safeCurrent = currentLoop.coerceAtLeast(0)
        val loop = if (totalLoops > 0) "loop $safeCurrent of $totalLoops" else "loop $safeCurrent"
        return "$state, $loop."
    }

    /**
     * Summary line for the brief Merge Complete card shown before
     * the auto-navigation to Library. Singular vs plural copy is
     * pinned in tests so future refactors do not silently degrade
     * accessibility output.
     */
    fun formatMergeCompleteSummary(clipCount: Int): String {
        val n = clipCount.coerceAtLeast(0)
        val noun = if (n == 1) "clip" else "clips"
        return "$n $noun · saved to Library"
    }
}
