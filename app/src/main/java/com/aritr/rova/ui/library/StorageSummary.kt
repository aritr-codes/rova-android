package com.aritr.rova.ui.library

/** Aggregated footprint over the Library rows (Polish P6). */
data class UsageSummary(val sessionCount: Int, val clipCount: Int, val totalBytes: Long)

/**
 * Pure: fold the row list into a footprint so the user can judge storage use and prune. A legacy row
 * (clipCount 0, no manifest) counts as 1 clip. Operates over the FULL library (not the filtered view)
 * so the footprint reflects everything on disk. Framework-free -> JVM-tested (house seam pattern).
 */
object UsageAggregator {
    fun aggregate(rows: List<LibraryRow>): UsageSummary = UsageSummary(
        sessionCount = rows.size,
        clipCount = rows.sumOf { it.clipCount.coerceAtLeast(1) },
        totalBytes = rows.sumOf { it.sizeBytes },
    )
}

/** Pure join for the usage line — parts arrive already localized (plurals + StorageFormat). */
object StorageSummaryFormatter {
    fun join(sessionsLabel: String, clipsLabel: String, sizeLabel: String): String =
        listOf(sessionsLabel, clipsLabel, sizeLabel).filter { it.isNotBlank() }.joinToString(" · ")
}
