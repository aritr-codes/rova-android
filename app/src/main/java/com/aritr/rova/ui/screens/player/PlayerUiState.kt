package com.aritr.rova.ui.screens.player

import com.aritr.rova.ui.text.UiText

/**
 * Phase 2.5 — surface state for the in-app Player route.
 *
 * Three terminal shapes:
 *   - [Loading]      : VM has not yet resolved the manifest. Default at construction.
 *   - [Ready]        : manifest resolved, artifact URI determined, ExoPlayer prepared.
 *   - [Unavailable]  : manifest missing, artifact unresolvable, or session id unknown.
 *
 * The state intentionally carries only data the screen renders: the URI
 * string (consumed by `MediaItem.fromUri`), the per-segment durations
 * (consumed by [SegmentedTimelineMath]), the requested per-clip duration
 * (mockup's "0:30 per clip" line — pulled from `manifest.config.durationSeconds`)
 * and the wall-clock `startedAt` that the top-bar formats via
 * `HistoryRowFormatters.formatPrimaryDateTime`.
 *
 * `playbackPositionMs` and `isPlaying` live on a sibling [PlaybackProgress]
 * snapshot so the static manifest-derived fields are not invalidated on
 * every position tick — Compose then only recomposes the timeline +
 * info-row when [PlaybackProgress] changes, not the whole screen.
 */
sealed interface PlayerUiState {
    data object Loading : PlayerUiState

    data class Ready(
        val mediaUri: String,
        val sessionId: String,
        val startedAt: Long,
        val segmentDurationsMs: List<Long>,
        val perClipDurationMs: Long,
        val totalClips: Int,
        /**
         * Audit F#4 — authoritative total duration from the manifest's
         * segment list. ExoPlayer-reported `duration` becomes the
         * source of truth once `STATE_READY` fires, but until then
         * (and for sessions whose last clip was truncated mid-clip per
         * Phase 1.5 carry-over) the segment sum is closer to reality
         * than `perClipDurationMs * totalClips`. Computed at resolve
         * time so the screen never has to re-walk the segment list.
         */
        val totalDurationFromSegmentsMs: Long,
        /**
         * PR-6b (ADR-0032) — per-clip wall-clock starts, sequence-ordered and
         * parallel to [segmentDurationsMs]. Always fully populated: exact where
         * the segment carried [com.aritr.rova.data.SegmentRecord.startedAtWallClock],
         * else synthesized (see [wallStartIsApproxMask]).
         */
        val segmentWallStartsMs: List<Long>,
        /**
         * PR-6b — parallel to [segmentWallStartsMs]; true where the wall-start
         * was synthesized (legacy schema <12 or recovered orphan). The player
         * shows an "approx" marker when the current clip's entry is true.
         */
        val wallStartIsApproxMask: List<Boolean>
    ) : PlayerUiState

    data class Unavailable(val reason: UiText) : PlayerUiState
}

/**
 * Position snapshot fed into the screen at ~250 ms cadence while
 * playback is live, plus a one-shot push on every play/pause/seek so
 * the UI reflects user-driven jumps instantly.
 *
 * Held separately from [PlayerUiState.Ready] to keep the manifest-derived
 * fields stable across position ticks (Compose recomposition gate).
 */
data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    /**
     * Player PR-6 Task 4 — true for the duration of a user drag-scrub
     * session (`beginScrub` → `endScrub`). The timeline reads this to
     * suppress the position-poll's authoritative `positionMs` while the
     * user's finger owns the thumb, and to render the scrub affordance.
     * Preserved across [PlayerViewModel.pushProgress] rebuilds so a poll
     * tick can never silently clear an in-flight scrub.
     */
    val isScrubbing: Boolean = false
)
