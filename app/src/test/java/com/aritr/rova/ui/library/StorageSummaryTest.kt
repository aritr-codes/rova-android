package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageSummaryTest {
    private fun row(clipCount: Int, sizeBytes: Long) = LibraryRow(
        stableKey = "k$sizeBytes",
        title = "t",
        dateLabel = "d",
        dateMillis = 0L,
        durationMs = 0L,
        sizeBytes = sizeBytes,
        clipCount = clipCount,
        topology = CaptureTopology.Single,
        badge = null,
        favorite = false,
    )

    @Test fun aggregate_countsSessionsClipsBytes() {
        val rows = listOf(
            row(clipCount = 12, sizeBytes = 84_000_000),
            row(clipCount = 1, sizeBytes = 6_000_000),
            row(clipCount = 0, sizeBytes = 19_000_000), // legacy row → counts as 1 clip
        )
        val s = UsageAggregator.aggregate(rows)
        assertEquals(3, s.sessionCount)
        assertEquals(14, s.clipCount) // 12 + 1 + max(1,0)
        assertEquals(109_000_000L, s.totalBytes)
    }

    @Test fun aggregate_emptyIsZero() {
        val s = UsageAggregator.aggregate(emptyList())
        assertEquals(0, s.sessionCount)
        assertEquals(0, s.clipCount)
        assertEquals(0L, s.totalBytes)
    }

    @Test fun join_dropsBlanks() {
        assertEquals(
            "3 sessions · 14 clips · 104 MB",
            StorageSummaryFormatter.join("3 sessions", "14 clips", "104 MB"),
        )
        assertEquals("3 sessions · 104 MB", StorageSummaryFormatter.join("3 sessions", "", "104 MB"))
    }
}
