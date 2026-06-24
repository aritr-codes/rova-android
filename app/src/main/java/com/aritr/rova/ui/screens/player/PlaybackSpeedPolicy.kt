package com.aritr.rova.ui.screens.player

import java.util.Locale
import kotlin.math.abs

/**
 * PR-7 — pure playback-speed policy for the player speed chip.
 *
 * Compose/ExoPlayer-free so it is JVM-unit-testable under
 * `isReturnDefaultValues = true`, mirroring the house pure-helper pattern
 * ([PlayerIconSpec], [SegmentedTimelineMath]).
 *
 * Speed set is the review affordance 0.5×/1×/1.5×/2× — 0.5× is slow-mo to
 * confirm a detail, 2× is the skim ceiling. 4× is deliberately DEFERRED
 * (spec §2.1, owner Q2): a review tool, not skim-for-hours.
 *
 * Speed is session-only (owner Q8): nothing here persists; the chip resets
 * to [DEFAULT] on the next player open.
 */
object PlaybackSpeedPolicy {

    /** Cycle order is the list order; [next] of the last wraps to the first. */
    val SPEEDS: List<Float> = listOf(0.5f, 1f, 1.5f, 2f)
    val DEFAULT: Float = 1f

    private const val EPSILON = 1e-4f

    /** True only for an exact listed speed. */
    fun isValid(speed: Float): Boolean = SPEEDS.any { abs(it - speed) < EPSILON }

    /** Index of the nearest supported speed (ties broken to the lower index). */
    private fun nearestIndex(speed: Float): Int =
        SPEEDS.indices.minByOrNull { abs(SPEEDS[it] - speed) } ?: 0

    /**
     * Next speed in cycle order, wrapping. Tolerant of an off-list current
     * value: snaps to the nearest supported speed, then advances one step.
     */
    fun next(current: Float): Float {
        val idx = if (isValid(current)) {
            SPEEDS.indexOfFirst { abs(it - current) < EPSILON }
        } else {
            nearestIndex(current)
        }
        return SPEEDS[(idx + 1) % SPEEDS.size]
    }

    /**
     * Defensive: coerce arbitrary input into range, then snap onto [SPEEDS].
     * Non-finite input (NaN/±Inf) resets to [DEFAULT] so a bad value can never
     * reach `ExoPlayer.setPlaybackSpeed` (Media3 requires speed > 0 / finite).
     */
    fun clampToSupported(speed: Float): Float {
        if (!speed.isFinite()) return DEFAULT
        val coerced = speed.coerceIn(SPEEDS.first(), SPEEDS.last())
        return SPEEDS[nearestIndex(coerced)]
    }

    /**
     * Locale-aware chip label, e.g. "1×" / "1.5×" (en) / "1,5×" (es).
     * Whole speeds render with no decimal. The "×" multiplier is part of the
     * formatted value, not user copy — pinned by [PlaybackSpeedPolicyTest].
     */
    fun label(speed: Float, locale: Locale): String {
        val isWhole = speed == speed.toLong().toFloat()
        val num = if (isWhole) {
            String.format(locale, "%d", speed.toLong())
        } else {
            String.format(locale, "%.1f", speed)
        }
        return "$num×"
    }
}
