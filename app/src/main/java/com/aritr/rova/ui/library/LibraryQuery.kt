package com.aritr.rova.ui.library

/**
 * ADR-0030 — pure Library query layer. [hero] picks the newest row; [collection]
 * filters (favorites / topology / search), sorts, and excludes the hero's key so
 * the same recording never appears twice (owner #4). Search matches title or
 * dateLabel substring, case-insensitive.
 */
object LibraryQuery {

    fun hero(rows: List<LibraryRow>): LibraryRow? = rows.maxByOrNull { it.dateMillis }

    fun collection(
        rows: List<LibraryRow>,
        sort: LibrarySort,
        filter: LibraryFilter,
        heroKey: String?,
    ): List<LibraryRow> {
        val q = filter.search.trim().lowercase()
        val filtered = rows.asSequence()
            .filter { it.stableKey != heroKey }
            .filter { !filter.favoritesOnly || it.favorite }
            .filter { filter.topology == null || it.topology == filter.topology }
            .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.dateLabel.lowercase().contains(q) }
            .toList()
        return when (sort) {
            LibrarySort.NEWEST -> filtered.sortedByDescending { it.dateMillis }
            LibrarySort.OLDEST -> filtered.sortedBy { it.dateMillis }
            LibrarySort.LONGEST -> filtered.sortedByDescending { it.durationMs }
            LibrarySort.LARGEST -> filtered.sortedByDescending { it.sizeBytes }
        }
    }
}
