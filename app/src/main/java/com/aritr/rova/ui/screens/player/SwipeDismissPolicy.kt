package com.aritr.rova.ui.screens.player

/**
 * player-gestures.html §06 — pure commit decision for swipe-down-to-dismiss.
 * Compose-free, JVM-unit-testable (house pure-helper pattern, mirror of
 * [AutoHideChromePolicy] / [EdgeSeekZones] / [PlaybackSpeedPolicy]).
 *
 * A vertical drag that BEGINS on the media body rubber-bands the frame with the
 * finger; releasing commits the dismiss when EITHER the travelled distance
 * reaches [COMMIT_DISTANCE_DP] (`--dismiss-commit:120dp`) OR the release is a
 * downward fling at or above [COMMIT_VELOCITY_DP_S] (`--dismiss-velocity:800dp/s`).
 * Otherwise the frame springs back (§11). Commit routes the EXISTING nav-pop —
 * ADR-0038 teardown unchanged, no new lifecycle, no backend.
 *
 * Both inputs are in dp / dp-per-second (the composable divides its px values by
 * density before calling), matching the spec's dp-declared thresholds. Only a
 * DOWNWARD gesture commits: distance and velocity are measured on the downward
 * axis (positive = down), so an upward drag/fling never clears the thresholds.
 * The generous distance + fling-only shortcut are the accidental-dismissal
 * mitigations §06 requires (a mid-review vertical nudge springs back).
 */
object SwipeDismissPolicy {

    /** `--dismiss-commit:120dp` (§06). Generous so a nudge never ejects. */
    const val COMMIT_DISTANCE_DP = 120f

    /** `--dismiss-velocity:800dp/s` (§06). Deliberate downward fling only. */
    const val COMMIT_VELOCITY_DP_S = 800f

    /** Rubber-band scale floor at the commit distance (decorative; §01 specimen). */
    const val SCALE_FLOOR = 0.94f

    /**
     * True when the release should commit the dismiss. [dragDy] is the total
     * downward travel (dp, ≥ 0 on the media body); [velocityDy] is the release
     * velocity on the downward axis (dp/s, positive = down).
     */
    fun shouldCommit(dragDy: Float, velocityDy: Float): Boolean =
        dragDy >= COMMIT_DISTANCE_DP || velocityDy >= COMMIT_VELOCITY_DP_S

    /**
     * Rubber-band scale for the tracked frame: `1.0` at rest, easing linearly
     * toward [SCALE_FLOOR] as the drag approaches [COMMIT_DISTANCE_DP]. Decorative
     * only — reduced-motion drops it (the translation tracking survives, §11).
     * Clamped so an over-drag past the commit distance never shrinks below the
     * floor.
     */
    fun dismissScale(dragDy: Float): Float {
        if (dragDy <= 0f) return 1f
        val progress = (dragDy / COMMIT_DISTANCE_DP).coerceIn(0f, 1f)
        return 1f - (1f - SCALE_FLOOR) * progress
    }
}
