package com.aritr.rova.ui.components

import com.aritr.rova.R
import com.aritr.rova.ui.text.UiText

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
 * i18n (B3 task 2b): user-facing display/announcement copy is now returned as
 * [UiText] tokens (resolved at the Compose/`Context` edge), so the helpers stay
 * pure and JVM-testable while no longer freezing localized English. Pure-numeric
 * formatters (`formatMmSs`, `formatClipProgressNumbers`, `computeClipProgress`)
 * emit only digits/separators and stay `String` — they carry no translatable copy.
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

    fun formatLoopPosition(currentLoop: Int, totalLoops: Int): UiText {
        val safeCurrent = currentLoop.coerceAtLeast(0)
        return if (totalLoops < 0) {
            UiText.StrArgs(R.string.record_hud_loop_position_continuous, listOf(safeCurrent))
        } else {
            UiText.StrArgs(R.string.record_hud_loop_position, listOf(safeCurrent, totalLoops))
        }
    }

    fun formatLoopsRemaining(currentLoop: Int, totalLoops: Int): UiText {
        if (totalLoops < 0) return UiText.Str(R.string.record_hud_loops_remaining_continuous)
        val safeTotal = totalLoops.coerceAtLeast(0)
        val remaining = (safeTotal - currentLoop).coerceAtLeast(0)
        return when {
            safeTotal == 0 -> UiText.Str(R.string.record_hud_loops_remaining_last)
            remaining == 0 -> UiText.Str(R.string.record_hud_loops_remaining_last)
            remaining == 1 -> UiText.StrArgs(R.string.record_hud_loops_remaining, listOf(1, safeTotal))
            else -> UiText.StrArgs(R.string.record_hud_loops_remaining, listOf(remaining, safeTotal))
        }
    }

    fun formatNextClipDurationLabel(clipSeconds: Int): UiText {
        val s = clipSeconds.coerceAtLeast(0)
        return when {
            s == 0 -> UiText.Str(R.string.record_hud_next_clip_momentary)
            s < 60 -> UiText.StrArgs(R.string.record_hud_next_clip_seconds, listOf(s))
            s == 60 -> UiText.Str(R.string.record_hud_next_clip_one_minute)
            s % 60 == 0 -> UiText.StrArgs(R.string.record_hud_next_clip_minutes, listOf(s / 60))
            else -> UiText.StrArgs(R.string.record_hud_next_clip_seconds, listOf(s))
        }
    }

    /**
     * Status line under the session timer for the recording variant.
     * Combines the user-facing quality token with a short flash-state
     * descriptor. Never exposes raw integer flash codes.
     *
     * [quality] and [flashLabel] must already be localized edge strings (the
     * caller resolves them at the Compose/`Context` edge); this helper only joins
     * them with the layout separator.
     */
    fun formatRecordingMeta(quality: String, flashLabel: String): UiText =
        UiText.StrArgs(R.string.record_hud_recording_meta, listOf(quality, flashLabel))

    fun formatFlashLabel(flashMode: Int): UiText = when (flashMode) {
        FLASH_MODE_AUTO -> UiText.Str(R.string.record_hud_flash_auto)
        FLASH_MODE_ON -> UiText.Str(R.string.record_hud_flash_on)
        else -> UiText.Str(R.string.record_hud_flash_off)
    }

    /**
     * Talkback-friendly announcement for the session-elapsed timer.
     * Rendered into a polite live region only on minute boundaries
     * (per UI_ROADMAP §"Slice 3 special requirements") — the caller
     * is responsible for the throttling; this helper only formats.
     *
     * Singular/plural hour & minute wording is encoded as one full-sentence
     * resource per branch (not Android `Plural`) so the externalized output stays
     * byte-identical to the prior hand-pluralized English.
     */
    fun formatElapsedAnnouncement(totalSeconds: Long): UiText {
        val s = totalSeconds.coerceAtLeast(0L)
        val hours = s / 3600
        val minutes = (s % 3600) / 60
        return when {
            hours > 0L && minutes > 0L -> {
                val id = when {
                    hours == 1L && minutes == 1L -> R.string.record_hud_elapsed_hour_minute
                    hours == 1L -> R.string.record_hud_elapsed_hour_minutes
                    minutes == 1L -> R.string.record_hud_elapsed_hours_minute
                    else -> R.string.record_hud_elapsed_hours_minutes
                }
                UiText.StrArgs(id, listOf(hours, minutes))
            }
            hours > 0L -> {
                val id = if (hours == 1L) R.string.record_hud_elapsed_hour else R.string.record_hud_elapsed_hours
                UiText.StrArgs(id, listOf(hours))
            }
            minutes == 0L -> UiText.Str(R.string.record_hud_elapsed_just_started)
            else -> {
                val id =
                    if (minutes == 1L) R.string.record_hud_elapsed_minute else R.string.record_hud_elapsed_minutes
                UiText.StrArgs(id, listOf(minutes))
            }
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
    fun formatMergeProgressNumbers(currentSegment: Int, totalSegments: Int): UiText {
        val total = totalSegments.coerceAtLeast(0)
        val current = currentSegment.coerceIn(0, total)
        return UiText.StrArgs(R.string.record_hud_merge_of, listOf(current, total))
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
    fun formatMergeRemaining(progress: Float): UiText? {
        val p = progress.coerceIn(0f, 1f)
        return when {
            p < 0.05f -> UiText.Str(R.string.record_hud_merge_starting)
            p >= 0.85f -> UiText.Str(R.string.record_hud_merge_almost)
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
    fun formatMergeAnnouncement(currentSegment: Int, totalSegments: Int): UiText {
        val total = totalSegments.coerceAtLeast(0)
        val current = currentSegment.coerceIn(0, total)
        if (total <= 0) return UiText.Str(R.string.record_hud_merge_preparing)
        if (current <= 0) return UiText.StrArgs(R.string.record_hud_merge_preparing_clips, listOf(total))
        return UiText.StrArgs(R.string.record_hud_merge_clip_of, listOf(current, total))
    }

    /**
     * TalkBack announcement for [com.aritr.rova.ui.components.SessionStatusCard]
     * (WCAG 2.2 AA SC 4.1.3, ADR-0020, SHAR-09). Published as the card's
     * content description in a polite live region — it carries the recording
     * state + loop position (not the fractional progress bar value), so it
     * re-announces on a loop roll, not on every progress frame.
     *
     * The state word ("Recording"/"Queued") is part of the localized copy, so
     * each state × loop-form combination is its own full-sentence resource rather
     * than an interpolated English fragment.
     */
    fun formatSessionStatusAnnouncement(
        isRecording: Boolean,
        currentLoop: Int,
        totalLoops: Int,
    ): UiText {
        val safeCurrent = currentLoop.coerceAtLeast(0)
        val hasTotal = totalLoops > 0
        val id = when {
            isRecording && hasTotal -> R.string.record_hud_session_status_recording_of
            isRecording -> R.string.record_hud_session_status_recording
            hasTotal -> R.string.record_hud_session_status_queued_of
            else -> R.string.record_hud_session_status_queued
        }
        return if (hasTotal) {
            UiText.StrArgs(id, listOf(safeCurrent, totalLoops))
        } else {
            UiText.StrArgs(id, listOf(safeCurrent))
        }
    }

    /**
     * Summary line for the brief Merge Complete card shown before
     * the auto-navigation to Library. Singular vs plural copy is
     * pinned in tests so future refactors do not silently degrade
     * accessibility output.
     */
    fun formatMergeCompleteSummary(clipCount: Int): UiText {
        val n = clipCount.coerceAtLeast(0)
        val id = if (n == 1) {
            R.string.record_hud_merge_complete_summary_one
        } else {
            R.string.record_hud_merge_complete_summary_other
        }
        return UiText.StrArgs(id, listOf(n))
    }

    // ─── PR-6b (ADR-0032) — wall-clock playhead ───────────────────

    /**
     * Time-of-day readout for the wall-clock playhead. Pure
     * java.text/java.util (JVM-testable, DST + locale correct via [zone] +
     * [locale]; the instant's own offset is applied since [zone] resolves DST
     * per-instant). [withDate] prepends a short weekday when the session spans
     * midnight (see
     * [com.aritr.rova.ui.screens.player.WallClockTimeline.spansMidnight]).
     */
    fun formatTimeOfDay(
        instantMs: Long,
        zone: java.util.TimeZone,
        locale: java.util.Locale,
        is24h: Boolean,
        withDate: Boolean,
    ): String {
        val pattern = when {
            withDate && is24h -> "EEE HH:mm:ss"
            withDate -> "EEE h:mm:ss a"
            is24h -> "HH:mm:ss"
            else -> "h:mm:ss a"
        }
        val fmt = java.text.SimpleDateFormat(pattern, locale)
        fmt.timeZone = zone
        return fmt.format(java.util.Date(instantMs))
    }

    /**
     * Inter-clip gap label ("+15 min gap"). Caller passes only a positive gap
     * ([com.aritr.rova.ui.screens.player.WallClockTimeline] suppresses
     * non-positive); rounds to whole minutes at/over 60 s, else whole seconds.
     */
    fun formatWallClockGap(gapMs: Long): UiText {
        val g = gapMs.coerceAtLeast(0L)
        return if (g >= 60_000L) {
            UiText.StrArgs(R.string.player_wallclock_gap_minutes, listOf((g / 60_000L).toInt()))
        } else {
            UiText.StrArgs(R.string.player_wallclock_gap_seconds, listOf((g / 1_000L).toInt()))
        }
    }
}
