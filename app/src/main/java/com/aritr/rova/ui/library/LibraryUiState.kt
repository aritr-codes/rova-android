package com.aritr.rova.ui.library

/**
 * Render state for the session-list Library surface (ADR-0030 amendment 2026-07-02). [rows] are
 * newest-first, DualShot sessions already aggregated to one row (LibrarySessionAggregator); the
 * screen layer filters/sorts/day-groups via LibraryQuery + LibraryDayGrouping. [hasLoaded] mirrors
 * the VM's first-load latch (drives loading placeholder vs empty CTA).
 */
data class LibraryUiState(
    val rows: List<LibraryRow> = emptyList(),
    val hasLoaded: Boolean = false,
    /**
     * Aggregated footprint over [rows] (the FULL library, not the filtered view) — drives the usage
     * summary line (Polish P6). Pure in-memory fold via [UsageAggregator]; no extra disk read.
     */
    val usage: UsageSummary = UsageSummary(0, 0, 0),
    /** Session-list row density (spec §3.7); seeded from RovaSettings.libraryDensity, reseeded on resume. */
    val density: LibraryDensity = LibraryDensity.COMFORTABLE,
)
