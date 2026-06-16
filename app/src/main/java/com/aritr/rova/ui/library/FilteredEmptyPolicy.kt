package com.aritr.rova.ui.library

/**
 * Which "filtered to nothing" empty body to show (M2, 2026-06-16). [LibraryStatePolicy] resolves the
 * coarse body state (Loading / Empty / SearchEmpty / Content); when it lands on SearchEmpty this picks
 * the right COPY so the empty state TEACHES the active facet instead of always showing search wording.
 *
 * Precedence: a real text query wins (it's the most specific intent), then a single-facet filter gets
 * its educational copy, and any combination (e.g. Favorites AND DualShot) falls back to Generic — a
 * combined miss has no single thing to teach. Framework-free → JVM-tested (house seam pattern).
 */
enum class FilteredEmptyKind { Search, Favorites, DualShot, Generic }

object FilteredEmptyPolicy {
    fun resolve(hasSearch: Boolean, favoritesOnly: Boolean, isDualShot: Boolean): FilteredEmptyKind = when {
        hasSearch -> FilteredEmptyKind.Search
        favoritesOnly && !isDualShot -> FilteredEmptyKind.Favorites
        isDualShot && !favoritesOnly -> FilteredEmptyKind.DualShot
        else -> FilteredEmptyKind.Generic
    }
}
