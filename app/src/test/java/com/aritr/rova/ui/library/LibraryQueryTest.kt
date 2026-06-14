package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryQueryTest {

    private fun row(
        key: String, date: Long, dur: Long = 0, size: Long = 0,
        topology: CaptureTopology = CaptureTopology.Single,
        favorite: Boolean = false, title: String = key, dateLabel: String = "",
    ) = LibraryRow(key, title, dateLabel, date, dur, size, topology, null, favorite)

    private val a = row("a", date = 300, dur = 10, size = 100, title = "Beach run")
    private val b = row("b", date = 200, dur = 50, size = 30, favorite = true, title = "Park")
    private val c = row("c", date = 100, dur = 5, size = 999, topology = CaptureTopology.DualShot)
    private val all = listOf(b, c, a) // intentionally unsorted

    @Test fun `hero is the newest by date`() {
        assertEquals("a", LibraryQuery.hero(all)!!.stableKey)
    }

    @Test fun `hero is null for empty`() {
        assertEquals(null, LibraryQuery.hero(emptyList()))
    }

    @Test fun `collection excludes the hero and sorts newest-first`() {
        val out = LibraryQuery.collection(all, LibrarySort.NEWEST, LibraryFilter(), heroKey = "a")
        assertEquals(listOf("b", "c"), out.map { it.stableKey })
    }

    @Test fun `oldest sort`() {
        val out = LibraryQuery.collection(all, LibrarySort.OLDEST, LibraryFilter(), heroKey = null)
        assertEquals(listOf("c", "b", "a"), out.map { it.stableKey })
    }

    @Test fun `longest then largest sorts`() {
        assertEquals(
            listOf("b", "a", "c"),
            LibraryQuery.collection(all, LibrarySort.LONGEST, LibraryFilter(), heroKey = null).map { it.stableKey }
        )
        assertEquals(
            listOf("c", "a", "b"),
            LibraryQuery.collection(all, LibrarySort.LARGEST, LibraryFilter(), heroKey = null).map { it.stableKey }
        )
    }

    @Test fun `favorites filter`() {
        val out = LibraryQuery.collection(all, LibrarySort.NEWEST, LibraryFilter(favoritesOnly = true), heroKey = null)
        assertEquals(listOf("b"), out.map { it.stableKey })
    }

    @Test fun `topology filter`() {
        val out = LibraryQuery.collection(all, LibrarySort.NEWEST, LibraryFilter(topology = CaptureTopology.DualShot), heroKey = null)
        assertEquals(listOf("c"), out.map { it.stableKey })
    }

    @Test fun `search matches title case-insensitively`() {
        val out = LibraryQuery.collection(all, LibrarySort.NEWEST, LibraryFilter(search = "beach"), heroKey = null)
        assertEquals(listOf("a"), out.map { it.stableKey })
    }
}
