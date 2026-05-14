package com.aritr.rova.service.dualrecord.internal

import com.aritr.rova.service.dualrecord.VideoSide

/**
 * Phase 6.1a — pure routing planner for the AudioFanOut broadcast pattern.
 * Drives `AudioFanOut` (Task 11): for each encoded AAC sample, which
 * muxers receive a `writeSampleData` call? Once both sides are stopped
 * or EOS has been sent, writes are rejected.
 *
 *  - `stopSide(side)`: idempotent removal of a side from the live set.
 *  - `markEosSent()`: terminal; subsequent `planWrite`/`planEos` reject.
 *  - `planWrite()` / `planEos()`: returns a `Route` with the set of live
 *    sides, or `Reject` if no sides are live or EOS has been sent.
 */
internal class AudioFanOutPlanner {

    sealed class Decision {
        data class Route(val sides: Set<VideoSide>) : Decision()
        object Reject : Decision()
    }

    private val liveSides = mutableSetOf(VideoSide.PORTRAIT, VideoSide.LANDSCAPE)
    private var eosSent = false

    fun stopSide(side: VideoSide) {
        liveSides.remove(side)
    }

    fun markEosSent() {
        eosSent = true
    }

    fun planWrite(): Decision =
        if (eosSent || liveSides.isEmpty()) Decision.Reject
        else Decision.Route(liveSides.toSet())

    fun planEos(): Decision =
        if (eosSent || liveSides.isEmpty()) Decision.Reject
        else Decision.Route(liveSides.toSet())
}
