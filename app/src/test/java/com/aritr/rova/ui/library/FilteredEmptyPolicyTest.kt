package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** M2 — pure resolver for the filtered-empty copy (educational vs search wording). */
class FilteredEmptyPolicyTest {

    @Test
    fun textSearch_winsOverEveryFacet() {
        // A real query is the most specific intent — its copy ("name or date") always applies.
        assertEquals(
            FilteredEmptyKind.Search,
            FilteredEmptyPolicy.resolve(hasSearch = true, favoritesOnly = true, isDualShot = true),
        )
        assertEquals(
            FilteredEmptyKind.Search,
            FilteredEmptyPolicy.resolve(hasSearch = true, favoritesOnly = false, isDualShot = false),
        )
    }

    @Test
    fun favoritesOnly_getsFavoritesCopy() {
        assertEquals(
            FilteredEmptyKind.Favorites,
            FilteredEmptyPolicy.resolve(hasSearch = false, favoritesOnly = true, isDualShot = false),
        )
    }

    @Test
    fun dualShotOnly_getsDualShotCopy() {
        assertEquals(
            FilteredEmptyKind.DualShot,
            FilteredEmptyPolicy.resolve(hasSearch = false, favoritesOnly = false, isDualShot = true),
        )
    }

    @Test
    fun combinedFacets_fallBackToGeneric() {
        // Favorites AND DualShot together has no single thing to teach → generic search-empty copy.
        assertEquals(
            FilteredEmptyKind.Generic,
            FilteredEmptyPolicy.resolve(hasSearch = false, favoritesOnly = true, isDualShot = true),
        )
    }

    @Test
    fun noFilter_isGeneric() {
        // Not normally reached (genuinely-empty routes to LibraryEmpty), but the resolver stays total.
        assertEquals(
            FilteredEmptyKind.Generic,
            FilteredEmptyPolicy.resolve(hasSearch = false, favoritesOnly = false, isDualShot = false),
        )
    }
}
