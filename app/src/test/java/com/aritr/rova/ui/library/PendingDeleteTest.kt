package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDeleteTest {
    private fun row(k: String) = LibraryRow(
        stableKey = k, title = k, dateLabel = "", dateMillis = 0, durationMs = 0,
        sizeBytes = 0, topology = com.aritr.rova.data.CaptureTopology.Single, badge = null, favorite = false,
    )

    @Test fun visibleRows_hidePendingKeys() {
        val pending = PendingDelete(setOf("b"))
        val rows = listOf(row("a"), row("b"), row("c"))
        assertEquals(listOf("a", "c"), pending.visible(rows).map { it.stableKey })
    }

    @Test fun none_hidesNothing() {
        val rows = listOf(row("a"), row("b"))
        assertEquals(rows, PendingDelete.NONE.visible(rows))
    }

    @Test fun isPending_reflectsMembership() {
        val p = PendingDelete(setOf("a"))
        assertTrue(p.isPending("a"))
        assertFalse(p.isPending("b"))
    }

    @Test fun restoreFailed_unhidesOnlyFailedKeys() {
        // Commit returned failures for "b"; "a","c" really deleted.
        val p = PendingDelete(setOf("a", "b", "c"))
        val after = p.restore(setOf("b"))
        // "b" must become visible again; "a"/"c" stay hidden (VM refresh removes them).
        assertEquals(emptySet<String>(), after.keys.intersect(setOf("b")))
        assertEquals(setOf("a", "c"), after.keys)
    }

    @Test fun isEmpty_whenNoKeys() {
        assertTrue(PendingDelete.NONE.isEmpty)
        assertFalse(PendingDelete(setOf("a")).isEmpty)
    }
}
