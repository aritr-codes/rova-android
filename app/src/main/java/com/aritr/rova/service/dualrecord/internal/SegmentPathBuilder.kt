package com.aritr.rova.service.dualrecord.internal

import com.aritr.rova.service.dualrecord.VideoSide
import java.io.File

/**
 * Phase 6.1a — single source of truth for the paired-segment naming
 * convention. `segment_NNNN_<P|L>.mp4`, co-located in the session
 * directory.
 *
 * Phase 6.1b's recovery brainstorm extends `RecoveryScanner.SEGMENT_REGEX`
 * to match this pattern; the regex and this builder MUST agree on the
 * suffix shape. Any change here must update the regex in lockstep.
 */
internal object SegmentPathBuilder {

    /**
     * @param sessionDir target directory (existence not checked).
     * @param sequence 1..9999. Throws `IllegalArgumentException` if outside.
     * @param side `PORTRAIT` → `_P`, `LANDSCAPE` → `_L`.
     */
    fun build(sessionDir: File, sequence: Int, side: VideoSide): File {
        require(sequence in 1..9999) { "sequence must be 1..9999, was $sequence" }
        val suffix = when (side) {
            VideoSide.PORTRAIT -> "P"
            VideoSide.LANDSCAPE -> "L"
        }
        return File(sessionDir, "segment_%04d_%s.mp4".format(sequence, suffix))
    }
}
