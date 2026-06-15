package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryStatePolicyTest {
    @Test fun loading_beforeFirstLoad() {
        assertEquals(
            LibraryStateKind.Loading,
            LibraryStatePolicy.resolve(hasLoaded = false, isEmpty = true, hasActiveQuery = false),
        )
    }

    @Test fun content_whenRowsPresent() {
        assertEquals(
            LibraryStateKind.Content,
            LibraryStatePolicy.resolve(hasLoaded = true, isEmpty = false, hasActiveQuery = true),
        )
    }

    @Test fun emptyLibrary_whenLoadedAndNoQuery() {
        assertEquals(
            LibraryStateKind.Empty,
            LibraryStatePolicy.resolve(hasLoaded = true, isEmpty = true, hasActiveQuery = false),
        )
    }

    @Test fun searchEmpty_whenLoadedEmptyWithQuery() {
        assertEquals(
            LibraryStateKind.SearchEmpty,
            LibraryStatePolicy.resolve(hasLoaded = true, isEmpty = true, hasActiveQuery = true),
        )
    }
}
