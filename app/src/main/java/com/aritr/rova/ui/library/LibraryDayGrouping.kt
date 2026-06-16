package com.aritr.rova.ui.library

import com.aritr.rova.ui.screens.HistoryRowFormatters
import java.util.Locale
import java.util.TimeZone

/** ADR-0030 / spec §4 — one day bucket: header label, per-day size total, rows. */
data class LibraryDayGroup(
    val label: String,
    val sizeTotalLabel: String,
    val rows: List<LibraryRow>,
)

/**
 * Pure day-grouping for the Library grid/list. Rows arrive pre-sorted (newest
 * first), so same-day rows are contiguous; this folds them into [LibraryDayGroup]s
 * preserving order, labelling via [HistoryRowFormatters.formatGroupHeader] and
 * summing sizes via [StorageFormat.dayTotal]. Framework-free → JVM-testable.
 */
object LibraryDayGrouping {

    /**
     * Bug-fix 2026-06-16 (sort crash on Largest/Longest) — group only when the active [sort] is
     * chronological. [group] folds same-day rows assuming they are contiguous (true for NEWEST/
     * OLDEST). Under LONGEST/LARGEST the collection is size/duration-ordered, so a given day's rows
     * scatter and [group] emits the SAME day label in multiple non-adjacent buckets. The render then
     * keys header items as `hdr-<label>` → duplicate LazyList keys → IllegalArgumentException crash.
     * For non-chronological sorts we return a single header-less bucket (label = "" → the caller
     * suppresses the day header), which is also the correct UX (flat list, no scattered date headers).
     */
    fun groupForSort(
        rows: List<LibraryRow>,
        sort: LibrarySort,
        nowMillis: Long,
        locale: Locale,
        tz: TimeZone,
    ): List<LibraryDayGroup> {
        if (rows.isEmpty()) return emptyList()
        if (sort.isChronological) return group(rows, nowMillis, locale, tz)
        return listOf(
            LibraryDayGroup(
                label = "",
                sizeTotalLabel = StorageFormat.dayTotal(rows.map { it.sizeBytes }, locale),
                rows = rows,
            ),
        )
    }

    fun group(
        rows: List<LibraryRow>,
        nowMillis: Long,
        locale: Locale,
        tz: TimeZone,
    ): List<LibraryDayGroup> {
        if (rows.isEmpty()) return emptyList()
        val out = ArrayList<LibraryDayGroup>()
        var bucketLabel: String? = null
        var bucket = ArrayList<LibraryRow>()
        fun flush() {
            val label = bucketLabel ?: return
            out += LibraryDayGroup(
                label = label,
                sizeTotalLabel = StorageFormat.dayTotal(bucket.map { it.sizeBytes }, locale),
                rows = bucket.toList(),
            )
        }
        for (r in rows) {
            val label = HistoryRowFormatters.formatGroupHeader(r.dateMillis, nowMillis, locale, tz)
            if (label != bucketLabel) {
                flush()
                bucketLabel = label
                bucket = ArrayList()
            }
            bucket += r
        }
        flush()
        return out
    }
}
