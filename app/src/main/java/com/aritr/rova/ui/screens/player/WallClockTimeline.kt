package com.aritr.rova.ui.screens.player

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * PR-6b (ADR-0032) — pure math for the wall-clock playhead. Given per-clip
 * resolved wall-starts (sequence-ordered, same order as footage), clip
 * durations, an approx mask, and a flat playback position, computes the
 * recorded instant + whether an inter-clip gap precedes the current clip.
 *
 * Boundary contract (matches SegmentedTimelineMath): at an internal clip
 * boundary, select the NEXT clip with intra-offset 0; at/after total
 * duration, select the LAST clip at its end. gapBeforeMs belongs to the
 * SELECTED clip; a negative value (clock/DST/manual adjust) is suppressed —
 * never rendered as a "gap".
 */
internal object WallClockTimeline {

    data class Readout(val instantMs: Long, val isApprox: Boolean, val gapBeforeMs: Long?)

    fun readoutAt(
        wallStartsMs: List<Long>,
        durationsMs: List<Long>,
        approxMask: List<Boolean>,
        positionMs: Long,
    ): Readout {
        if (wallStartsMs.isEmpty() || durationsMs.isEmpty()) {
            return Readout(0L, isApprox = true, gapBeforeMs = null)
        }
        val n = minOf(wallStartsMs.size, durationsMs.size)
        val total = (0 until n).sumOf { durationsMs[it].coerceAtLeast(0L) }
        val clamped = positionMs.coerceIn(0L, total)

        // Select clip: first clip whose cumulative END is strictly > clamped;
        // at exact boundary clamped == end advances to next clip (offset 0);
        // at/after total, last clip.
        var consumed = 0L
        var idx = n - 1
        var clipStart = 0L
        for (i in 0 until n) {
            val dur = durationsMs[i].coerceAtLeast(0L)
            val end = consumed + dur
            if (clamped < end || (i == n - 1)) {
                idx = i
                clipStart = consumed
                break
            }
            consumed = end
        }
        val intraOffset = (clamped - clipStart).coerceAtLeast(0L)
        val instant = wallStartsMs[idx] + intraOffset

        val gap: Long? = if (idx > 0) {
            val prevEnd = wallStartsMs[idx - 1] + durationsMs[idx - 1].coerceAtLeast(0L)
            val raw = wallStartsMs[idx] - prevEnd
            if (raw > 0L) raw else null
        } else null

        val isApprox = approxMask.getOrElse(idx) { true }
        return Readout(instant, isApprox, gap)
    }

    /** True when first/last instants fall on different local calendar days. */
    fun spansMidnight(firstInstantMs: Long, lastInstantMs: Long, zone: TimeZone): Boolean {
        val dayFmt = SimpleDateFormat("yyyyDDD", Locale.US).apply { timeZone = zone }
        return dayFmt.format(Date(firstInstantMs)) != dayFmt.format(Date(lastInstantMs))
    }
}
