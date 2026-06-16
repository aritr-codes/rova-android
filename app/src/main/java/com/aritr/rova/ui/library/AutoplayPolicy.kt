package com.aritr.rova.ui.library

/**
 * Slice 4.2 — pure selection of which Library cards may autoplay. Autoplaying every card
 * exhausts hardware video decoders (MediaCodec instance limits + battery), so the Screen feeds
 * the VISIBLE card keys in viewport order and this returns the first [cap]. The hero, when on
 * screen, consumes ONE of the [MAX_CONCURRENT] budget — [cardCap] reserves for it. Framework-free
 * → JVM-tested.
 *
 * codex review (Slice 4.2): cap counts the hero (4 cards + hero = 5 was too aggressive); audio
 * decoders are freed in LibraryAutoplayVideo (track disabled, not just muted); a card must be
 * [MIN_VISIBLE_FRACTION] on-screen to claim a decoder (no sliver-edge thrash).
 */
object AutoplayPolicy {
    /** Max concurrent video players TOTAL (cards + hero). Conservative — mid-range decoders
     *  tolerate only a few instances; getMaxSupportedInstances is an upper hint, not a promise. */
    const val MAX_CONCURRENT = 3

    /** A card must be at least this fraction on-screen to autoplay (don't let a 1px edge steal a decoder). */
    const val MIN_VISIBLE_FRACTION = 0.5f

    /** Card budget after reserving one decoder for the hero when it's visible. */
    fun cardCap(heroVisible: Boolean): Int =
        (MAX_CONCURRENT - if (heroVisible) 1 else 0).coerceAtLeast(0)

    fun select(orderedVisibleKeys: List<String>, cap: Int): Set<String> {
        if (cap <= 0) return emptySet()
        return orderedVisibleKeys.take(cap).toSet()
    }

    /** True if a [size]px item at viewport-relative [top] is ≥[minFraction] inside [vpStart]..[vpEnd]. */
    fun isMostlyVisible(
        top: Int,
        size: Int,
        vpStart: Int,
        vpEnd: Int,
        minFraction: Float = MIN_VISIBLE_FRACTION,
    ): Boolean {
        if (size <= 0) return false
        val visible = (minOf(top + size, vpEnd) - maxOf(top, vpStart)).coerceAtLeast(0)
        return visible.toFloat() / size >= minFraction
    }
}
