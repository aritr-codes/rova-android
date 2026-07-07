package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMetadataEntryTest {

    @Test fun isEmpty_trueWhenAllDefault() {
        assertTrue(LibraryMetadataEntry().isEmpty())
    }

    @Test fun isEmpty_falseWhenPositionPresent() {
        assertFalse(LibraryMetadataEntry(positionsBySide = mapOf("" to 5L)).isEmpty())
    }

    @Test fun positionFor_returnsSlot_thenFallsBackToSingle() {
        val e = LibraryMetadataEntry(positionsBySide = mapOf("" to 100L))
        assertEquals(100L, e.positionFor(""))
        // P+L side with no own position falls back to single "" (legacy migration friendliness)
        assertEquals(100L, e.positionFor("PORTRAIT"))
    }

    @Test fun positionFor_sideSpecificWins_noFallbackWhenPresent() {
        val e = LibraryMetadataEntry(positionsBySide = mapOf("" to 100L, "PORTRAIT" to 7L))
        assertEquals(7L, e.positionFor("PORTRAIT"))
    }

    @Test fun positionFor_singleSlotNeverFallsBack() {
        assertNull(LibraryMetadataEntry().positionFor(""))
    }

    @Test fun withPosition_setsSlot_dropsNonPositive() {
        val e = LibraryMetadataEntry().withPosition("", 50L)
        assertEquals(50L, e.positionFor(""))
        val cleared = e.withPosition("", 0L)
        assertNull(cleared.positionFor(""))
    }

    @Test fun merge_favoriteOrTrue_neverUnfavorites() {
        val canonical = LibraryMetadataEntry(favorite = false)
        val legacy = LibraryMetadataEntry(favorite = true)
        assertTrue(LibraryMetadataEntry.merge(canonical, legacy)!!.favorite)
    }

    @Test fun merge_titleCanonicalWins_importsMissing() {
        val canonical = LibraryMetadataEntry(customTitle = "Keep")
        val legacy = LibraryMetadataEntry(customTitle = "Old")
        assertEquals("Keep", LibraryMetadataEntry.merge(canonical, legacy)!!.customTitle)
        assertEquals("Old", LibraryMetadataEntry.merge(LibraryMetadataEntry(), legacy)!!.customTitle)
    }

    @Test fun merge_lastPlayedAtTakesMax_notKeyWinner() {
        val canonical = LibraryMetadataEntry(lastPlayedAt = 10L)
        val legacy = LibraryMetadataEntry(lastPlayedAt = 99L)
        assertEquals(99L, LibraryMetadataEntry.merge(canonical, legacy)!!.lastPlayedAt)
    }

    @Test fun merge_positionsCanonicalPerSideWins_importsOthers() {
        val canonical = LibraryMetadataEntry(positionsBySide = mapOf("PORTRAIT" to 5L))
        val legacy = LibraryMetadataEntry(positionsBySide = mapOf("PORTRAIT" to 1L, "LANDSCAPE" to 9L))
        val merged = LibraryMetadataEntry.merge(canonical, legacy)!!
        assertEquals(5L, merged.positionFor("PORTRAIT"))
        assertEquals(9L, merged.positionFor("LANDSCAPE"))
    }

    @Test fun merge_bothNull_returnsNull_oneNull_returnsOther() {
        assertNull(LibraryMetadataEntry.merge(null, null))
        assertEquals("X", LibraryMetadataEntry.merge(null, LibraryMetadataEntry(customTitle = "X"))!!.customTitle)
    }

    @Test fun isEmpty_trueWhenOnlyNonPositivePositions() {
        assertTrue(LibraryMetadataEntry(positionsBySide = mapOf("" to 0L)).isEmpty())
    }

    // ADR-0037 §4 (codex BLOCKING finding 2026-07-07) — a kept-raw segment slot
    // must NEVER inherit the session-level "" position: that is exactly the
    // cross-artifact resume bleed the contract forbids.
    @Test
    fun `positionFor segment slot is exact match with no empty-slot fallback`() {
        val entry = LibraryMetadataEntry(positionsBySide = mapOf("" to 5_000L, "#seg2" to 9_000L))
        assertEquals(9_000L, entry.positionFor("#seg2"))
        assertNull(entry.positionFor("#seg0"))          // absent means absent
        // Legacy P+L grace fallback is preserved for side slots:
        assertEquals(5_000L, entry.positionFor("PORTRAIT"))
    }
}
