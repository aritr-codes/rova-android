package com.aritr.rova.ui.library

import java.security.MessageDigest

/**
 * ADR-0030 / spec §7 — disk thumbnail cache key. Hashes the row identity plus
 * invalidators (size, last-modified, duration) so a changed/replaced file at the
 * same path or content-URI never serves a stale thumbnail. Returns lowercase hex
 * (filesystem-safe cache filename stem). Pure.
 */
object ThumbnailCacheKey {

    fun keyFor(stableKey: String, sizeBytes: Long, lastModified: Long, durationMs: Long): String {
        val raw = "$stableKey|$sizeBytes|$lastModified|$durationMs"
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
