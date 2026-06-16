package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailCacheKeyTest {

    @Test fun `same inputs give same key`() {
        assertEquals(
            ThumbnailCacheKey.keyFor("/a.mp4", 100, 5, 10),
            ThumbnailCacheKey.keyFor("/a.mp4", 100, 5, 10),
        )
    }

    @Test fun `changing any invalidator changes the key`() {
        val base = ThumbnailCacheKey.keyFor("/a.mp4", 100, 5, 10)
        assertNotEquals(base, ThumbnailCacheKey.keyFor("/a.mp4", 101, 5, 10))
        assertNotEquals(base, ThumbnailCacheKey.keyFor("/a.mp4", 100, 6, 10))
        assertNotEquals(base, ThumbnailCacheKey.keyFor("/a.mp4", 100, 5, 11))
        assertNotEquals(base, ThumbnailCacheKey.keyFor("/b.mp4", 100, 5, 10))
    }

    @Test fun `key is a filesystem-safe hex string`() {
        val k = ThumbnailCacheKey.keyFor("content://x/1", 100, 5, 10)
        assertTrue(k.matches(Regex("[0-9a-f]+")))
    }
}
