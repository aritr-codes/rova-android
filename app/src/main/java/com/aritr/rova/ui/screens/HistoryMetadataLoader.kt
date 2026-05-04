package com.aritr.rova.ui.screens

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Slice 13A — pure helper that fans out per-path metadata extraction
 * for cache misses. Lives as an `internal object` so the History
 * pipeline can be JVM-tested without dragging in `MediaMetadataRetriever`
 * or the Android `Bitmap` class — the type parameter [V] keeps the
 * helper agnostic to what gets cached.
 *
 * The split between this helper and [VideoMetadataUtils.extractMetadata]
 * is intentional: production substitutes the real Android extractor;
 * tests substitute a deterministic `(path) -> String` and assert
 * cache-hit/miss bookkeeping without instrumented infrastructure.
 *
 * Concurrency: each missing path is extracted in its own [async] child
 * inheriting the caller's [CoroutineDispatcher]. When the caller runs
 * on `Dispatchers.IO` (`HistoryViewModel.refresh()` does), the 64-thread
 * `IO` pool runs the extracts in parallel, cutting cold-list wall time
 * from `O(n × per-file cost)` toward `O(per-file cost)` for small `n`.
 *
 * The cache must be safe for concurrent put — production passes a
 * [java.util.concurrent.ConcurrentHashMap]. Cache reads are non-locking
 * snapshots; a path that becomes "missing" between the snapshot and the
 * extract is harmlessly re-extracted (`fillMissing` does not promise
 * single-flight; it promises that every requested path ends up in the
 * cache exactly once after the call returns).
 */
internal object HistoryMetadataLoader {

    suspend fun <V> fillMissing(
        paths: List<String>,
        cache: MutableMap<String, V>,
        extract: suspend (String) -> V
    ) {
        val missing = paths.filter { it !in cache }
        if (missing.isEmpty()) return
        coroutineScope {
            missing.map { p ->
                async { cache[p] = extract(p) }
            }.awaitAll()
        }
    }
}
