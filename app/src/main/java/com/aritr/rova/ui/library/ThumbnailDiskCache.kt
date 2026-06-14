package com.aritr.rova.ui.library

import java.io.File

/**
 * ADR-0030 / spec §7 — disk thumbnail cache. Stores raw (already-encoded) bytes
 * under [dir], keyed by [ThumbnailCacheKey] output. Size-bounded LRU by file
 * last-modified. Pure file I/O → JVM-testable; the Bitmap↔WebP encode/decode is
 * the caller's Android seam.
 *
 * Thread-safety: the Slice-2 loader populates a grid from several coroutines at
 * once, so every mutating path ([put]/[removeAllExcept]) runs under [lock]; this
 * serialises the write→evict sequence. [evictIfNeeded] also never evicts the entry
 * just written (it is [protect]ed), so a put can never delete its own bytes even
 * when the host filesystem has coarse last-modified granularity (ties in the LRU
 * sort). [get] is a single atomic read and stays lock-free.
 */
class ThumbnailDiskCache(private val dir: File, private val maxBytes: Long) {

    private val lock = Any()

    init { if (!dir.exists()) dir.mkdirs() }

    private fun fileFor(key: String) = File(dir, "$key.thumb")

    fun get(key: String): ByteArray? {
        val f = fileFor(key)
        if (!f.exists()) return null
        f.setLastModified(System.currentTimeMillis()) // touch → LRU recency
        return f.readBytes()
    }

    fun put(key: String, bytes: ByteArray) {
        synchronized(lock) {
            fileFor(key).writeBytes(bytes)
            evictIfNeeded(protect = key)
        }
    }

    /** Delete cache files whose key is not in [keep] (deleted/moved-out rows). */
    fun removeAllExcept(keep: Set<String>) {
        synchronized(lock) {
            dir.listFiles()?.forEach { f ->
                val key = f.name.removeSuffix(".thumb")
                if (key !in keep) f.delete()
            }
        }
    }

    /** Caller holds [lock]. Never deletes [protect]'s file (the just-written entry). */
    private fun evictIfNeeded(protect: String? = null) {
        val protectedFile = protect?.let { fileFor(it) }
        val files = dir.listFiles()?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortBy { it.lastModified() } // oldest first
        for (f in files) {
            if (total <= maxBytes) break
            if (f == protectedFile) continue
            total -= f.length()
            f.delete()
        }
    }
}
