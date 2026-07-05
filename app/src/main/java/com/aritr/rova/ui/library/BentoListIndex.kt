package com.aritr.rova.ui.library

/**
 * Pure bookkeeping for the bento LazyColumn shape. ONE place computes item
 * order, keys, scrubber segments and per-key lookups so assembly and the rail
 * can never disagree (codex plan round: the old flattened assumptions in the
 * ScrubberIndex call site break with month dividers + 4 leading chrome items).
 *
 * Item order contract (frozen):
 *   [0] recovery/warnings   [1] stats   [2] chips   [3] vault door
 *   then per day (newest first): [monthDivider?] [header] [bentoRow…]
 *   then [endcap].
 */
object BentoListIndex {

    const val LEADING_ITEM_COUNT = 4

    sealed interface Entry {
        data class MonthDivider(val label: String) : Entry
        data class Header(val dayEpochMillis: Long) : Entry
        data class BentoRow(
            val dayEpochMillis: Long,
            val pattern: BentoRowPlanner.RowPattern,
            val memberKeys: List<String>, // stableKeys, in span order
        ) : Entry
    }

    data class Built(
        val entries: List<Entry>, // day-section entries ONLY (offset by LEADING_ITEM_COUNT in the real list)
        val keyForEntry: List<String>, // stable LazyColumn key per entry (same size as entries)
        val scrubberSegments: List<ScrubberSegment>, // label + absolute item indices (leading count already applied)
        val itemIndexByStableKey: Map<String, Int>, // tile stableKey -> absolute LazyColumn item index of its bento row
        val visibleStableKeys: Set<String>, // every tile key in the built list (selection prune input)
    )

    fun build(
        groups: List<LibraryDayGroup>,
        plans: List<List<BentoRowPlanner.RowPattern>>, // parallel to groups
        monthDividerLabels: List<String?>, // parallel to groups; non-null = divider BEFORE that day
        scrubberLabels: List<String>, // parallel to groups (short day labels)
    ): Built {
        val entries = ArrayList<Entry>()
        val keyForEntry = ArrayList<String>()
        val scrubberSegments = ArrayList<ScrubberSegment>()
        val itemIndexByStableKey = HashMap<String, Int>()
        val visibleStableKeys = HashSet<String>()

        for (i in groups.indices) {
            val group = groups[i]
            val patterns = plans.getOrElse(i) { emptyList() }
            val dividerLabel = monthDividerLabels.getOrNull(i)

            if (dividerLabel != null) {
                entries += Entry.MonthDivider(dividerLabel)
                keyForEntry += "month-$dividerLabel"
            }

            val headerAbsoluteIndex = LEADING_ITEM_COUNT + entries.size
            entries += Entry.Header(group.dayEpochMillis)
            keyForEntry += "hdr-${group.dayEpochMillis}"

            var rowCursor = 0
            for (pattern in patterns) {
                val n = pattern.spans.size
                val memberKeys = group.rows.subList(rowCursor, rowCursor + n).map { it.stableKey }
                rowCursor += n

                val rowAbsoluteIndex = LEADING_ITEM_COUNT + entries.size
                entries += Entry.BentoRow(group.dayEpochMillis, pattern, memberKeys)
                val digest = memberKeys.joinToString("|").hashCode().toUInt().toString(16)
                keyForEntry += "row-${group.dayEpochMillis}-$digest"

                memberKeys.forEach { key ->
                    itemIndexByStableKey[key] = rowAbsoluteIndex
                    visibleStableKeys += key
                }
            }
            check(rowCursor == group.rows.size) {
                "BentoRowPlanner plan for day ${group.dayEpochMillis} consumed $rowCursor of ${group.rows.size} rows"
            }

            // Leading divider is NOT counted here: it sits in the gap between the previous day's
            // last row and this day's header, uncovered by any segment's [start, end] range. That
            // gap still resolves to THIS day in ScrubberIndex.segmentIndexForItemIndex (ascending
            // indexOfFirst scan), so a rail jump onto the divider correctly lands on this header
            // without inflating this segment's range into the NEXT day's divider/header.
            val itemCount = 1 + patterns.size
            scrubberSegments += ScrubberSegment(scrubberLabels.getOrElse(i) { "" }, headerAbsoluteIndex, itemCount)
        }

        return Built(entries, keyForEntry, scrubberSegments, itemIndexByStableKey, visibleStableKeys)
    }
}
