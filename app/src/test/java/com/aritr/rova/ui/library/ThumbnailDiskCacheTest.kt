package com.aritr.rova.ui.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThumbnailDiskCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `put then get returns bytes`() {
        val cache = ThumbnailDiskCache(tmp.newFolder("thumbs"), maxBytes = 10_000)
        cache.put("k1", byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.get("k1"))
    }

    @Test fun `get returns null for a miss`() {
        val cache = ThumbnailDiskCache(tmp.newFolder("thumbs"), maxBytes = 10_000)
        assertNull(cache.get("nope"))
    }

    @Test fun `persists across instances`() {
        val dir = tmp.newFolder("thumbs")
        ThumbnailDiskCache(dir, maxBytes = 10_000).put("k", byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), ThumbnailDiskCache(dir, maxBytes = 10_000).get("k"))
    }

    @Test fun `evicts least-recently-used when over budget`() {
        val cache = ThumbnailDiskCache(tmp.newFolder("thumbs"), maxBytes = 300)
        cache.put("a", ByteArray(200))
        cache.put("b", ByteArray(200)) // total 400 > 300 → evict LRU ("a")
        assertNull(cache.get("a"))
        assertTrue(cache.get("b") != null)
    }

    @Test fun `removeAllExcept prunes deleted rows`() {
        val cache = ThumbnailDiskCache(tmp.newFolder("thumbs"), maxBytes = 10_000)
        cache.put("keep", byteArrayOf(1))
        cache.put("drop", byteArrayOf(2))
        cache.removeAllExcept(setOf("keep"))
        assertTrue(cache.get("keep") != null)
        assertNull(cache.get("drop"))
    }
}
