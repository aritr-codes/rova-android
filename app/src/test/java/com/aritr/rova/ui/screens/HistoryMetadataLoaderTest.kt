package com.aritr.rova.ui.screens

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Slice 13A — JVM tests for the cache-fill helper that backs the
 * History parallel metadata path. Substitutes a deterministic
 * `(String) -> String` extractor in place of `MediaMetadataRetriever`
 * so the bookkeeping (cache hits skip extraction, misses are extracted
 * exactly once, mixed runs only touch misses, empty input no-ops) can
 * be verified without instrumentation.
 *
 * The "extract" lambda is non-suspending in these tests, so under
 * `runBlocking`'s single-threaded dispatcher every async runs to
 * completion sequentially in launch order. That is fine for these
 * assertions: parallelism in production is structural (many threads
 * in `Dispatchers.IO`); correctness is structural (every requested
 * path ends up in the cache exactly once).
 */
class HistoryMetadataLoaderTest {

    @Test
    fun `cache hits skip extraction`() = runBlocking {
        val cache = ConcurrentHashMap<String, String>().apply {
            put("a", "cached_a")
            put("b", "cached_b")
        }
        var calls = 0
        HistoryMetadataLoader.fillMissing(
            paths = listOf("a", "b"),
            cache = cache,
            extract = { calls++; "extracted_$it" }
        )
        assertEquals(0, calls)
        assertEquals("cached_a", cache["a"])
        assertEquals("cached_b", cache["b"])
    }

    @Test
    fun `misses get extracted and cached`() = runBlocking {
        val cache = ConcurrentHashMap<String, String>()
        val callCount = AtomicInteger(0)
        HistoryMetadataLoader.fillMissing(
            paths = listOf("a", "b", "c"),
            cache = cache,
            extract = {
                callCount.incrementAndGet()
                "v_$it"
            }
        )
        assertEquals(3, callCount.get())
        assertEquals("v_a", cache["a"])
        assertEquals("v_b", cache["b"])
        assertEquals("v_c", cache["c"])
    }

    @Test
    fun `mixed hits and misses extract only misses`() = runBlocking {
        val cache = ConcurrentHashMap<String, String>().apply {
            put("a", "cached_a")
        }
        val extracted = java.util.Collections.synchronizedList(mutableListOf<String>())
        HistoryMetadataLoader.fillMissing(
            paths = listOf("a", "b", "c"),
            cache = cache,
            extract = {
                extracted.add(it)
                "v_$it"
            }
        )
        assertEquals(setOf("b", "c"), extracted.toSet())
        assertEquals("cached_a", cache["a"])
        assertEquals("v_b", cache["b"])
        assertEquals("v_c", cache["c"])
    }

    @Test
    fun `empty paths is a no-op`() = runBlocking {
        val cache = ConcurrentHashMap<String, String>()
        HistoryMetadataLoader.fillMissing(
            paths = emptyList(),
            cache = cache,
            extract = { fail("extractor must not run for empty paths"); "" }
        )
        assertTrue(cache.isEmpty())
    }

    @Test
    fun `duplicate paths still extract once per occurrence but cache holds latest`() = runBlocking {
        // Defensive: the helper does not de-dupe input. If the caller
        // hands in duplicates, every duplicate triggers an extract
        // (because the `it !in cache` snapshot is taken before any
        // extract runs). The cache ends up with the last write — fine
        // for production where extract is deterministic on path.
        val cache = ConcurrentHashMap<String, String>()
        val callCount = AtomicInteger(0)
        HistoryMetadataLoader.fillMissing(
            paths = listOf("a", "a", "a"),
            cache = cache,
            extract = {
                callCount.incrementAndGet()
                "v_$it"
            }
        )
        assertEquals(3, callCount.get())
        assertEquals("v_a", cache["a"])
    }
}
