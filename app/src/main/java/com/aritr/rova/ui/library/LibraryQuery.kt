package com.aritr.rova.ui.library

/**
 * ADR-0030 — pure Library query layer. [collection] filters (favorites / topology /
 * search), sorts, and excludes [heroKey] (always null since PR-B; retained for API
 * stability per codex — the hero/grid showcase it deduped against was removed by the
 * PR-B session-list redesign). Search matches title or dateLabel substring, case-insensitive.
 */
object LibraryQuery {

    fun collection(
        rows: List<LibraryRow>,
        sort: LibrarySort,
        filter: LibraryFilter,
        heroKey: String?,
    ): List<LibraryRow> {
        val filtered = rows.asSequence()
            .filter { it.stableKey != heroKey }
            .filter { matches(it, filter) }
            .toList()
        return when (sort) {
            LibrarySort.NEWEST -> filtered.sortedByDescending { it.dateMillis }
            LibrarySort.OLDEST -> filtered.sortedBy { it.dateMillis }
            LibrarySort.LONGEST -> filtered.sortedByDescending { it.durationMs }
            LibrarySort.LARGEST -> filtered.sortedByDescending { it.sizeBytes }
        }
    }

    /** Facet/search predicate used by [collection]. */
    private fun matches(row: LibraryRow, filter: LibraryFilter): Boolean {
        val q = filter.search.trim().lowercase()
        return (!filter.favoritesOnly || row.favorite) &&
            (filter.topology == null || row.topology == filter.topology) &&
            (q.isEmpty() || row.title.lowercase().contains(q) || row.dateLabel.lowercase().contains(q))
    }
}
