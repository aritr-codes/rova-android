package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BentoListIndexTest {

    private val p33 = BentoRowPlanner.RowPattern(listOf(3, 3), 152)
    private val p6 = BentoRowPlanner.RowPattern(listOf(6), 192)

    private fun rowFixture(key: String) = LibraryRow(
        stableKey = key,
        title = "t",
        dateLabel = "d",
        dateMillis = 0L,
        durationMs = 0L,
        sizeBytes = 0L,
        clipCount = 1,
        topology = CaptureTopology.Single,
        badge = null,
        favorite = false,
    )

    private fun groupOf(epoch: Long, keys: List<String>) = LibraryDayGroup(
        label = "L$epoch",
        sizeTotalLabel = "0",
        rows = keys.map { rowFixture(it) },
        dayEpochMillis = epoch,
    )

    @Test
    fun `no divider - absolute indexes offset by LEADING_ITEM_COUNT`() {
        val group = groupOf(100L, listOf("k1", "k2"))
        val built = BentoListIndex.build(
            groups = listOf(group),
            plans = listOf(listOf(p33)),
            monthDividerLabels = listOf(null),
            scrubberLabels = listOf("Today"),
        )
        assertEquals(2, built.entries.size) // header + 1 row
        assertEquals(BentoListIndex.Entry.Header(100L), built.entries[0])
        assertEquals("hdr-100", built.keyForEntry[0])
        assertTrue(built.keyForEntry[1].startsWith("row-100-"))
        assertEquals(5, built.itemIndexByStableKey["k1"]) // LEADING(4) + header(1) = row at 5
        assertEquals(5, built.itemIndexByStableKey["k2"])
    }

    @Test
    fun `divider shifts subsequent indices by one`() {
        val group = groupOf(200L, listOf("k1"))
        val built = BentoListIndex.build(
            groups = listOf(group),
            plans = listOf(listOf(p6)),
            monthDividerLabels = listOf("July"),
            scrubberLabels = listOf("Today"),
        )
        assertEquals(3, built.entries.size) // divider + header + row
        assertEquals("month-July", built.keyForEntry[0])
        assertEquals("hdr-200", built.keyForEntry[1])
        // header absolute index = LEADING(4) + divider(1) = 5; row = 6
        assertEquals(6, built.itemIndexByStableKey["k1"])
    }

    @Test
    fun `segment starts point at headers, itemCount excludes the leading divider`() {
        val g0 = groupOf(100L, listOf("k1", "k2"))
        val g1 = groupOf(50L, listOf("k3"))
        val built = BentoListIndex.build(
            groups = listOf(g0, g1),
            plans = listOf(listOf(p33), listOf(p6)),
            monthDividerLabels = listOf(null, "June"),
            scrubberLabels = listOf("Today", "Yesterday"),
        )
        // g0: no divider -> header at LEADING(4), itemCount = header+1row = 2
        assertEquals(ScrubberSegment("Today", 4, 2), built.scrubberSegments[0])
        // g1: divider present at index 6 (after g0's header+row); header at 7; itemCount = header+1row = 2
        // (divider excluded from itemCount so this segment's range doesn't swallow the next day's gap)
        assertEquals(ScrubberSegment("Yesterday", 7, 2), built.scrubberSegments[1])
    }

    @Test
    fun `itemIndexByStableKey finds a mid-day tile row, not the first row`() {
        val group = groupOf(100L, listOf("k1", "k2", "k3"))
        val built = BentoListIndex.build(
            groups = listOf(group),
            plans = listOf(listOf(p33, p6)), // row0=[k1,k2] row1=[k3]
            monthDividerLabels = listOf(null),
            scrubberLabels = listOf("Today"),
        )
        val row0Index = built.itemIndexByStableKey["k1"]
        val row1Index = built.itemIndexByStableKey["k3"]
        assertNotEquals(row0Index, row1Index)
        assertEquals(row0Index, built.itemIndexByStableKey["k2"])
    }

    @Test
    fun `membership digest changes key when a member leaves but lead tile stays`() {
        val groupA = groupOf(100L, listOf("k1", "k2"))
        val a = BentoListIndex.build(
            groups = listOf(groupA),
            plans = listOf(listOf(p33)),
            monthDividerLabels = listOf(null),
            scrubberLabels = listOf("Today"),
        )
        val groupB = groupOf(100L, listOf("k1"))
        val b = BentoListIndex.build(
            groups = listOf(groupB),
            plans = listOf(listOf(p6)),
            monthDividerLabels = listOf(null),
            scrubberLabels = listOf("Today"),
        )
        assertNotEquals(a.keyForEntry.last(), b.keyForEntry.last())
    }

    @Test
    fun `visibleStableKeys is the union of every row's members`() {
        val g0 = groupOf(100L, listOf("k1", "k2"))
        val g1 = groupOf(50L, listOf("k3"))
        val built = BentoListIndex.build(
            groups = listOf(g0, g1),
            plans = listOf(listOf(p33), listOf(p6)),
            monthDividerLabels = listOf(null, null),
            scrubberLabels = listOf("Today", "Yesterday"),
        )
        assertEquals(setOf("k1", "k2", "k3"), built.visibleStableKeys)
    }

    @Test
    fun `empty groups yields an empty Built`() {
        val built = BentoListIndex.build(
            groups = emptyList(),
            plans = emptyList(),
            monthDividerLabels = emptyList(),
            scrubberLabels = emptyList(),
        )
        assertTrue(built.entries.isEmpty())
        assertTrue(built.keyForEntry.isEmpty())
        assertTrue(built.scrubberSegments.isEmpty())
        assertTrue(built.itemIndexByStableKey.isEmpty())
        assertTrue(built.visibleStableKeys.isEmpty())
        assertNull(built.itemIndexByStableKey["missing"])
    }
}
