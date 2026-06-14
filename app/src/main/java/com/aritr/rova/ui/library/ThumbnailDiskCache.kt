package com.aritr.rova.ui.library

import java.io.File

/**
 * ADR-0030 / spec §7 — disk thumbnail cache. Stores raw (already-encoded) bytes
 * under [dir], keyed by [ThumbnailCacheKey] output. Size-bounded LRU by file
 * last-modified. Pure file I/O → JVM-testable; the Bitmap↔WebP encode/decode is
 * the caller's Android seam.
 */
class ThumbnailDiskCache(private val dir: File, private val maxBytes: Long) {

    init { if (!dir.exists()) dir.mkdirs() }

    private fun fileFor(key: String) = File(dir, "$key.thumb")

    fun get(key: String): ByteArray? {
        val f = fileFor(key)
        if (!f.exists()) return null
        f.setLastModified(System.currentTimeMillis()) // touch → LRU recency
        return f.readBytes()
    }

    fun put(key: String, bytes: ByteArray) {
        fileFor(key).writeBytes(bytes)
        evictIfNeeded()
    }

    /** Delete cache files whose key is not in [keep] (deleted/moved-out rows). */
    fun removeAllExcept(keep: Set<String>) {
        dir.listFiles()?.forEach { f ->
            val key = f.name.removeSuffix(".thumb")
            if (key !in keep) f.delete()
        }
    }

    private fun evictIfNeeded() {
        val files = dir.listFiles()?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortBy { it.lastModified() } // oldest first
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }
}
