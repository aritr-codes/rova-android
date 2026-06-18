package com.aritr.rova.ui.theme

import kotlin.math.floor

/**
 * Pure rotation math for the branded "merge in progress" glyph (ADR-0031 §6/§8,
 * Icon P2 Track A). Framework-free so it is JVM-unit-testable under
 * `isReturnDefaultValues = true` — the house pure-helper seam pattern. The
 * framework-touching part is the thin [com.aritr.rova.ui.components.ProcessingGlyph].
 */
object MergeMotion {

    /** Milliseconds for one full 360° revolution of the merge glyph. */
    const val SPIN_PERIOD_MS: Int = 1400

    /**
     * Rotation in degrees for an animation [fraction] (driven 0f→1f by an infinite
     * transition). [fraction] is wrapped into [0f,1f) before mapping to [0f,360f),
     * so the `1f→0f` restart is seamless (360° ≡ 0°). When [reduceMotion] is true
     * the glyph is held static at 0° (WCAG 2.2 AA SC 2.3.3 / 2.2.2): meaning
     * survives without motion because both host surfaces also show "Merging" text.
     */
    fun angle(fraction: Float, reduceMotion: Boolean): Float {
        if (reduceMotion) return 0f
        val wrapped = fraction - floor(fraction)
        return wrapped * 360f
    }
}
