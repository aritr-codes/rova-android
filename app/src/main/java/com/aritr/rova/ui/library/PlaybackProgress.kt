package com.aritr.rova.ui.library

import com.aritr.rova.ui.screens.player.ResumePolicy

/**
 * v3.3 playback-progress hairline predicate (frozen spec, docs/design/library-bento.html) —
 * the Compose transcription of the spec's `pgFrac`. Pure (house pure-helper pattern).
 *
 * The hairline is a RESUME-POINT indicator, not a watch-history ledger: it renders iff the
 * player would resume, so the show/hide rule is [ResumePolicy] verbatim — position > 0 AND
 * duration known AND not inside the near-end restart window. Never-opened and fully-reviewed
 * both return null (render bare). Width is truthful and unclamped; the fraction is only
 * capped at 1 defensively.
 */
object PlaybackProgress {

    /** Bar width fraction in (0, 1], or null = no bar. */
    fun fraction(positionMs: Long?, durationMs: Long): Float? {
        val pos = positionMs ?: return null
        if (pos <= 0L || durationMs <= 0L) return null
        if (ResumePolicy.shouldRestartFromStart(pos, durationMs)) return null
        return (pos.toDouble() / durationMs).toFloat().coerceAtMost(1f)
    }

    /** Spoken percent for the tile label ("partially watched N%") — Math.round of the spec. */
    fun percent(fraction: Float): Int = Math.round(fraction * 100f)
}
