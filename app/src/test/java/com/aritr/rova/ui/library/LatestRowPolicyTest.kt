package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestRowPolicyTest {

    private fun row(key: String) = LibraryRow(
        stableKey = key, title = "t", dateLabel = "d", dateMillis = 1L,
        durationMs = 1L, sizeBytes = 1L, clipCount = 1,
        topology = CaptureTopology.Single, badge = null, favorite = false,
    )

    private val rows = listOf(row("newest"), row("older"))

    @Test
    fun newestSort_firstVisibleRowIsLatest() {
        assertEquals("newest", LatestRowPolicy.latestKey(rows, LibrarySort.NEWEST))
    }

    @Test
    fun nonNewestSorts_noAccent() {
        assertNull(LatestRowPolicy.latestKey(rows, LibrarySort.OLDEST))
        assertNull(LatestRowPolicy.latestKey(rows, LibrarySort.LONGEST))
        assertNull(LatestRowPolicy.latestKey(rows, LibrarySort.LARGEST))
    }

    @Test
    fun emptyList_noAccent() {
        assertNull(LatestRowPolicy.latestKey(emptyList(), LibrarySort.NEWEST))
    }
}
