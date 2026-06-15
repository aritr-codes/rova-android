package com.aritr.rova.ui.library

/**
 * ADR-0030 — pure Library query layer. [hero] picks the newest row overall;
 * [heroFor] picks the newest row that MATCHES the active filter/search so the
 * hero tracks the visible set (spec §5.1, Slice 4). [collection] filters
 * (favorites / topology / search), sorts, and excludes the hero's key so the
 * same recording never appears twice (owner #4). Search matches title or
 * dateLabel substring, case-insensitive.
 */
object LibraryQuery {

    fun hero(rows: List<LibraryRow>): LibraryRow? = rows.maxByOrNull { it.dateMillis }

    /** Slice 4 — newest row matching [filter]; null when nothing matches. */
    fun heroFor(rows: List<LibraryRow>, filter: LibraryFilter): LibraryRow? =
        rows.asSequence().filter { matches(it, filter) }.maxByOrNull { it.dateMillis }

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

    /** Shared facet/search predicate (DRY between [heroFor] and [collection]). */
    private fun matches(row: LibraryRow, filter: LibraryFilter): Boolean {
        val q = filter.search.trim().lowercase()
        return (!filter.favoritesOnly || row.favorite) &&
            (filter.topology == null || row.topology == filter.topology) &&
            (q.isEmpty() || row.title.lowercase().contains(q) || row.dateLabel.lowercase().contains(q))
    }
}
