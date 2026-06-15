package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology

/**
 * ADR-0030 — pure row model the Library grid/list/hero render and [LibraryQuery]
 * operates over. Built by the ViewModel from VideoItem + SessionManifest + the
 * sidecar; carries no Android types so it is fully JVM-testable.
 *
 * @property title resolved display title (customTitle ?: SmartTitle).
 * @property dateLabel pre-formatted date string used for search matching.
 */
data class LibraryRow(
    val stableKey: String,
    val title: String,
    val dateLabel: String,
    val dateMillis: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    /**
     * Number of persisted/playable clips (segments) in the session — the same set the player's
     * SegmentedTimeline renders. Polish P3 (session identity). 0 for legacy file-scan rows with no
     * manifest (the clip-count chip then hides; usage aggregation counts such a row as 1 clip).
     */
    val clipCount: Int,
    val topology: CaptureTopology,
    val badge: LibraryBadge?,
    val favorite: Boolean,
)

/** Sort options for the Library (decision C). */
enum class LibrarySort { NEWEST, OLDEST, LONGEST, LARGEST }

/**
 * Filter facets (decision C). [topology] null = any. [search] blank = no search.
 * Vault is a separate destination, not a filter here (ADR-0030).
 */
data class LibraryFilter(
    val favoritesOnly: Boolean = false,
    val topology: CaptureTopology? = null,
    val search: String = "",
)
