package com.aritr.rova.ui.screens.player

/**
 * PR-6b — canonical per-side segment ordering for the wall-clock playhead.
 * Mirrors RecoveryScanner.SEGMENT_REGEX's 4-digit sequence
 * (`segment_NNNN[_P|_L].mp4`). The merged MP4 concatenates clips in
 * sequence order; DualShot's two async persist coroutines can append the two
 * sides out of manifest-list order (codex review #2), so the player must
 * order by parsed sequence, not list position, to keep wall-starts aligned
 * with footage.
 */
internal object SegmentOrdering {
    private val SEQUENCE = Regex("""segment_(\d{4})(?:_[PL])?\.mp4$""")

    fun parseSequence(filename: String): Int? =
        SEQUENCE.find(filename)?.groupValues?.get(1)?.toIntOrNull()

    /** Original indices reordered by (sequence ?: MAX) then stably by index. */
    fun orderedIndices(filenames: List<String>): List<Int> =
        filenames.indices.sortedWith(
            compareBy({ parseSequence(filenames[it]) ?: Int.MAX_VALUE }, { it })
        )
}
