package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSidecarMergeTest {

    @Test
    fun customTitle_firstNonNull_portraitWins() {
        val portrait = LibraryMetadataEntry(customTitle = "from-portrait")
        val landscape = LibraryMetadataEntry(customTitle = "from-landscape")
        val merged = SessionSidecarMerge.resolve(null, listOf(portrait, landscape))
        assertEquals("from-portrait", merged?.customTitle)
    }

    @Test
    fun customTitle_canonicalBeatsEitherSide() {
        val canonical = LibraryMetadataEntry(customTitle = "canonical")
        val merged = SessionSidecarMerge.resolve(
            canonical,
            listOf(LibraryMetadataEntry(customTitle = "p"), LibraryMetadataEntry(customTitle = "l")),
        )
        assertEquals("canonical", merged?.customTitle)
    }

    @Test
    fun favorite_isEitherSide() {
        val merged = SessionSidecarMerge.resolve(
            null,
            listOf(LibraryMetadataEntry(favorite = false), LibraryMetadataEntry(favorite = true)),
        )
        assertTrue(merged!!.favorite)
    }

    @Test
    fun lastPlayedAt_isMaxAcrossAll() {
        val merged = SessionSidecarMerge.resolve(
            LibraryMetadataEntry(lastPlayedAt = 100L),
            listOf(LibraryMetadataEntry(lastPlayedAt = 300L), LibraryMetadataEntry(lastPlayedAt = 200L)),
        )
        assertEquals(300L, merged?.lastPlayedAt)
    }

    @Test
    fun nullSides_andNullCanonical_yieldNull() {
        assertNull(SessionSidecarMerge.resolve(null, listOf(null, null)))
    }

    @Test
    fun positions_union_earlierEntryWinsPerSlot() {
        val canonical = LibraryMetadataEntry(positionsBySide = mapOf("PORTRAIT" to 10L))
        val legacy = LibraryMetadataEntry(positionsBySide = mapOf("PORTRAIT" to 99L, "LANDSCAPE" to 20L))
        val merged = SessionSidecarMerge.resolve(canonical, listOf(legacy))
        assertEquals(10L, merged?.positionsBySide?.get("PORTRAIT"))
        assertEquals(20L, merged?.positionsBySide?.get("LANDSCAPE"))
    }
}
