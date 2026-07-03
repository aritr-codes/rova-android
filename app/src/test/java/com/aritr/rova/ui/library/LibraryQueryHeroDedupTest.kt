package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins the `collection` heroKey-dedup invariant: a row matching [heroKey] is excluded
 * so it never renders twice. The hero/grid showcase this originally deduped against was
 * removed by the PR-B session-list redesign; `heroKey` is retained on `collection` for
 * API stability (always null in production now) — these tests exercise it directly with
 * literal keys rather than via the deleted `LibraryQuery.hero`. Pure JVM.
 */
class LibraryQueryHeroDedupTest {

    private fun row(key: String, date: Long) =
        LibraryRow(key, key, "", date, 0, 0, 1, CaptureTopology.Single, null, false)

    @Test fun `matching heroKey is excluded from the collection — single representation`() {
        val rows = listOf(row("c", 300), row("b", 200), row("a", 100)) // newest first
        val collection = LibraryQuery.collection(rows, LibrarySort.NEWEST, LibraryFilter(), heroKey = "c")
        assertFalse(
            "heroKey must not also appear in the collection",
            collection.any { it.stableKey == "c" },
        )
        assertEquals(listOf("b", "a"), collection.map { it.stableKey })
    }

    @Test fun `single row with matching heroKey yields an empty collection (no duplicate)`() {
        val rows = listOf(row("only", 100))
        val collection = LibraryQuery.collection(rows, LibrarySort.NEWEST, LibraryFilter(), heroKey = "only")
        assertEquals(emptyList<String>(), collection.map { it.stableKey })
    }

    @Test fun `empty rows yield an empty collection`() {
        assertEquals(
            emptyList<String>(),
            LibraryQuery.collection(emptyList(), LibrarySort.NEWEST, LibraryFilter(), null).map { it.stableKey },
        )
    }
}
