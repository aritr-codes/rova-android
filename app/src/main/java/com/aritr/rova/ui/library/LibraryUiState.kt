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
)
