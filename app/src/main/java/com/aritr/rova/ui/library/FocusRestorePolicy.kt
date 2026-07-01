package com.aritr.rova.ui.library

/**
 * Pure helper for Slice-5 focus restore (WCAG 2.2 AA remediation-backlog row 23 subset): maps a
 * launched library row's `stableKey` to its flattened lazy-list item index so the caller can
 * `scrollToItem(index)` before requesting focus. The same index sequence works for grid and list
 * because every grid cell is its own keyed lazy item.
 *
 * Item order mirrors [LibraryScreen]'s lazy content: index 0 = recovery/warning header,
 * index 1 = hero (when present), then for each day-group a header item followed by its row items.
 */
object FocusRestorePolicy {

    /**
     * @param pendingKey the stableKey of the row that launched playback.
     * @param heroKey the hero row's stableKey, or null when no hero is shown.
     * @param groupRowKeys per-day-group ordered stableKeys (each inner list = one group's rows).
     * @return the lazy item index to scroll to, or null if [pendingKey] is blank or not found.
     */
    fun targetItemIndex(
        pendingKey: String,
        heroKey: String?,
        groupRowKeys: List<List<String>>,
    ): Int? {
        if (pendingKey.isBlank()) return null
        var idx = 1 // [0] = recovery/warning header
        if (heroKey != null) {
            if (pendingKey == heroKey) return idx
            idx++
        }
        for (rows in groupRowKeys) {
            idx++ // day header
            for (key in rows) {
                if (key == pendingKey) return idx
                idx++
            }
        }
        return null
    }

    /**
     * True when a scroll is needed to bring [pendingKey] into view before restoring focus. On the
     * normal player→Library return the saveable [rememberLazyGridState] has already restored the
     * pre-open scroll position, so the opened tile is still among [visibleKeys] — scrolling then is
     * redundant and jump-scrolls the grid away from the user while stalling the main thread
     * (jitter root cause, device-verified 2026-07-01, RZCYA1VBQ2H). Only scroll when the target
     * genuinely isn't on screen (rare: state not restored). Focus is requested either way, so
     * gating the scroll — not the whole restore — keeps focus restore for every input modality
     * (D-pad / Switch Access / keyboard / TalkBack), unlike a touch-exploration-only gate.
     */
    fun shouldScroll(pendingKey: String, visibleKeys: Collection<String>): Boolean =
        pendingKey !in visibleKeys
}
