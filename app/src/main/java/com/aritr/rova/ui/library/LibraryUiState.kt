package com.aritr.rova.ui.library

/** Library grid vs list toggle (decision A). */
enum class LibraryViewMode { GRID, LIST }

/**
 * Render state for the redesigned Library surface. [rows] are newest-first
 * (the screen layer extracts the hero + day-groups the remainder via
 * LibraryQuery + LibraryDayGrouping). [hasLoaded] mirrors the VM's first-load
 * latch (drives loading placeholder vs empty CTA).
 */
data class LibraryUiState(
    val rows: List<LibraryRow> = emptyList(),
    val viewMode: LibraryViewMode = LibraryViewMode.GRID,
    val hasLoaded: Boolean = false,
    /**
     * Aggregated footprint over [rows] (the FULL library, not the filtered view) — drives the usage
     * summary line (Polish P6). Pure in-memory fold via [UsageAggregator]; no extra disk read.
     */
    val usage: UsageSummary = UsageSummary(0, 0, 0),
    /**
     * Polish P7 — whether Library grid/list cards may autoplay a muted preview. Mirrors
     * [com.aritr.rova.data.RovaSettings.libraryCardPreview] (default OFF). Reseeded on resume so a
     * Settings toggle takes effect when returning to the (kept-composed) Library tab. The hero is NOT
     * gated by this — it always autoplays (subject to reduce-motion).
     */
    val cardPreview: Boolean = false,
)
