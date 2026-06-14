package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Pins the owner-mandated hero single-representation invariant (adjustment 1): the
 * newest recording renders in EXACTLY one place. `LibraryQuery.hero`/`collection`
 * are Slice-1 helpers; this regression test guards against a future refactor of the
 * screen or the query silently reintroducing a duplicate hero. Pure JVM.
 */
class LibraryQueryHeroDedupTest {

    private fun row(key: String, date: Long) =
        LibraryRow(key, key, "", date, 0, 0, CaptureTopology.Single, null, false)

    @Test fun `hero is excluded from the collection — single representation`() {
        val rows = listOf(row("c", 300), row("b", 200), row("a", 100)) // newest first
        val hero = LibraryQuery.hero(rows)
        assertNotNull(hero)
        assertEquals("c", hero!!.stableKey)
        val collection = LibraryQuery.collection(rows, LibrarySort.NEWEST, LibraryFilter(), hero.stableKey)
        assertFalse(
            "hero stableKey must not also appear in the collection",
            collection.any { it.stableKey == hero.stableKey },
        )
        assertEquals(listOf("b", "a"), collection.map { it.stableKey })
    }

    @Test fun `single row yields a hero and an empty collection (no duplicate)`() {
        val rows = listOf(row("only", 100))
        val hero = LibraryQuery.hero(rows)
        val collection = LibraryQuery.collection(rows, LibrarySort.NEWEST, LibraryFilter(), hero?.stableKey)
        assertEquals("only", hero?.stableKey)
        assertEquals(emptyList<String>(), collection.map { it.stableKey })
    }

    @Test fun `empty rows yield no hero and no collection`() {
        assertEquals(null, LibraryQuery.hero(emptyList()))
        assertEquals(
            emptyList<String>(),
            LibraryQuery.collection(emptyList(), LibrarySort.NEWEST, LibraryFilter(), null).map { it.stableKey },
        )
    }
}
