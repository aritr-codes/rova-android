package com.aritr.rova.ui.library

/**
 * Latest-row accent eligibility (spec §3.3). The anchor applies ONLY under NEWEST sort —
 * under OLDEST the newest session is at the bottom, and under size/duration sorts a
 * "latest" accent would contradict the sort the user chose. Filter-awareness comes free:
 * callers pass the already-filtered visible collection (matches old heroFor semantics
 * without a second surface).
 */
object LatestRowPolicy {
    fun latestKey(visibleRows: List<LibraryRow>, sort: LibrarySort): String? =
        if (sort == LibrarySort.NEWEST) visibleRows.firstOrNull()?.stableKey else null
}
