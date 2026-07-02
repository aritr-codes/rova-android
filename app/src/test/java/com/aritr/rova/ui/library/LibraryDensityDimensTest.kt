package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDensityDimensTest {

    @Test
    fun comfortable_matchesSpecValues() {
        val s = LibraryDensityDimens.spec(LibraryDensity.COMFORTABLE)
        assertEquals(104, s.thumbWidthDp)
        assertEquals(60, s.thumbHeightDp)
        assertEquals(128, s.latestThumbWidthDp)
        assertEquals(74, s.latestThumbHeightDp)
        assertEquals(64, s.rowMinHeightDp)
    }

    @Test
    fun compact_matchesSpecValues() {
        val s = LibraryDensityDimens.spec(LibraryDensity.COMPACT)
        assertEquals(84, s.thumbWidthDp)
        assertEquals(50, s.thumbHeightDp)
        assertEquals(112, s.latestThumbWidthDp)
        assertEquals(64, s.latestThumbHeightDp)
        assertEquals(56, s.rowMinHeightDp)
    }

    @Test
    fun bothDensities_keepRowsAtOrAbove48dpTouchTarget() {
        LibraryDensity.entries.forEach { d ->
            assertTrue(LibraryDensityDimens.spec(d).rowMinHeightDp >= 48) // WCAG/ADR-0020 floor
        }
    }

    @Test
    fun compact_isStrictlySmallerThanComfortable() {
        val c = LibraryDensityDimens.spec(LibraryDensity.COMFORTABLE)
        val k = LibraryDensityDimens.spec(LibraryDensity.COMPACT)
        assertTrue(k.thumbWidthDp < c.thumbWidthDp)
        assertTrue(k.rowMinHeightDp < c.rowMinHeightDp)
    }
}
