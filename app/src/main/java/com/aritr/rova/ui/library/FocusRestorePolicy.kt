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
}
