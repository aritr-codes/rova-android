package com.aritr.rova.ui.screens.player

/**
 * Phase 2.5 — pure math behind the segmented clip timeline shown on
 * `04-video-player.html`. Maps a flat playback position (in ms) onto
 * a per-segment {Done | Current(fillFraction) | Upcoming} layout so
 * the composable can render each cell without re-walking the segment
 * list itself.
 *
 * Pulled out of [SegmentedTimeline] (composable) and [PlayerViewModel]
 * so JVM unit tests can pin the boundary-crossing behavior without
 * any Compose / ExoPlayer machinery.
 *
 * Boundary semantics:
 *   - At position == sum(durations[0..i]) (the exact end of segment i),
 *     segment i is [Cell.Done] and segment i+1 is [Cell.Current] with
 *     fillFraction = 0f. This matches the visual mockup where a clip
 *     "finishes" the moment the boundary is crossed.
 *   - When the total position equals or exceeds the sum of all segment
 *     durations, every cell becomes [Cell.Done] (playback at end).
 *   - On an empty segment list, the math returns a single
 *     [Cell.Current] of fraction 0f. Defensive — empty manifests
 *     should be filtered out by [PlayerUriResolver] before reaching
 *     here, but the composable should still render predictably.
 *   - Zero-duration segments (degenerate manifest) are treated as
 *     instantly-done if the position has reached their start, and
 *     do not divide-by-zero.
 */
internal object SegmentedTimelineMath {

    sealed interface Cell {
        data object Done : Cell
        data class Current(val fillFraction: Float) : Cell
        data object Upcoming : Cell
    }

    data class TimelineState(
        val cells: List<Cell>,
        /** 1-based current clip index for the "Clip N of M" info row. */
        val currentClipIndex: Int,
        val totalClips: Int
    )

    fun compute(segmentDurationsMs: List<Long>, positionMs: Long): TimelineState {
        if (segmentDurationsMs.isEmpty()) {
            return TimelineState(
                cells = listOf(Cell.Current(0f)),
                currentClipIndex = 1,
                totalClips = 1
            )
        }
        val total = segmentDurationsMs.sum()
        val clamped = positionMs.coerceIn(0L, total)
        val cells = ArrayList<Cell>(segmentDurationsMs.size)
        var consumed = 0L
        var current = -1
        for ((i, dur) in segmentDurationsMs.withIndex()) {
            val start = consumed
            val end = consumed + dur
            when {
                // Past this segment entirely. End-of-stream collapses
                // to all-done because clamped == total when position is
                // at or beyond the end.
                clamped >= end && (clamped > start || dur == 0L) && (i < segmentDurationsMs.size - 1 || clamped >= total) -> {
                    cells.add(Cell.Done)
                }
                clamped >= start && current == -1 -> {
                    val fraction = if (dur == 0L) 0f else {
                        ((clamped - start).toFloat() / dur.toFloat())
                            .coerceIn(0f, 1f)
                    }
                    cells.add(Cell.Current(fraction))
                    current = i
                }
                else -> {
                    cells.add(Cell.Upcoming)
                }
            }
            consumed = end
        }
        // If clamped == total, every cell ended up Done. Surface the
        // last segment as the "current" index for the info row so the
        // user does not see "Clip 0 of N".
        val currentIndex = when {
            current >= 0 -> current + 1
            else -> segmentDurationsMs.size
        }
        return TimelineState(
            cells = cells,
            currentClipIndex = currentIndex,
            totalClips = segmentDurationsMs.size
        )
    }

    fun totalDurationMs(durations: List<Long>): Long = durations.sumOf { it.coerceAtLeast(0L) }

    fun fractionFromPosition(positionMs: Long, durations: List<Long>): Float {
        val total = totalDurationMs(durations)
        if (total <= 0L) return 0f
        return (positionMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    fun positionFromFraction(fraction: Float, durations: List<Long>): Long {
        val total = totalDurationMs(durations)
        if (total <= 0L) return 0L
        return (fraction.coerceIn(0f, 1f) * total).toLong().coerceIn(0L, total)
    }

    fun segmentStartMs(index: Int, durations: List<Long>): Long {
        var acc = 0L
        for (i in 0 until index.coerceIn(0, durations.size)) acc += durations[i].coerceAtLeast(0L)
        return acc
    }

    fun segmentAtPosition(positionMs: Long, durations: List<Long>): Int {
        if (durations.isEmpty()) return 0
        val clamped = positionMs.coerceIn(0L, totalDurationMs(durations))
        var acc = 0L
        for ((i, d) in durations.withIndex()) {
            acc += d.coerceAtLeast(0L)
            if (clamped < acc) return i
        }
        return durations.lastIndex
    }

    fun nextSegmentStart(positionMs: Long, durations: List<Long>): Long? {
        val cur = segmentAtPosition(positionMs, durations)
        if (cur >= durations.lastIndex) return null
        return segmentStartMs(cur + 1, durations)
    }

    fun prevSegmentStart(positionMs: Long, durations: List<Long>, restartCurrentAfterMs: Long = 750L): Long? {
        if (durations.isEmpty() || positionMs <= 0L) return null
        val cur = segmentAtPosition(positionMs, durations)
        val curStart = segmentStartMs(cur, durations)
        return if (positionMs - curStart > restartCurrentAfterMs) curStart
        else if (cur == 0) null
        else segmentStartMs(cur - 1, durations)
    }

    fun snapIfNear(positionMs: Long, durations: List<Long>, snapWindowMs: Long): Long {
        var acc = 0L
        val boundaries = ArrayList<Long>(durations.size + 1).apply { add(0L) }
        for (d in durations) { acc += d.coerceAtLeast(0L); boundaries.add(acc) }
        val nearest = boundaries.minByOrNull { kotlin.math.abs(it - positionMs) } ?: return positionMs
        return if (kotlin.math.abs(nearest - positionMs) <= snapWindowMs) nearest else positionMs
    }

    fun cellWeights(durations: List<Long>): List<Float> {
        val total = totalDurationMs(durations)
        if (total <= 0L) return durations.map { 1f / durations.size.coerceAtLeast(1) }
        return durations.map { it.coerceAtLeast(0L).toFloat() / total.toFloat() }
    }
}
