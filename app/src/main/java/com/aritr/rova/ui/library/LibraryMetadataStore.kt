package com.aritr.rova.ui.library

import java.io.File

/**
 * ADR-0030 — file-backed sidecar for Library UI metadata (favorite / rename /
 * lastPlayedAt), keyed by row `stableKey`. NEVER touches SessionManifest, so the
 * terminal-state write race cannot occur. Atomic temp-then-rename writes mirror
 * SessionStore's durability contract.
 *
 * Thread-safety: in-memory map guarded by a lock; each write rewrites the whole
 * file (the map is tiny — one small record per recording). Callers invoke off the
 * main thread.
 *
 * Construct ONE instance per [filesDir] (production: the `RovaApp` lazy prop). The
 * in-memory cache is per-instance, so a second instance over the same directory
 * would serve stale reads after the first one writes; and the single fixed temp
 * file assumes a single OS process (Android app default). Both hold under the
 * app's single-process, single-lazy-prop wiring — do not bypass it (e.g. a second
 * store built in a separate `:process` or WorkManager process).
 *
 * @param filesDir a stable app-internal directory (production: `context.filesDir`).
 */
class LibraryMetadataStore(private val filesDir: File) {

    private val target = File(filesDir, FILE_NAME)
    private val tmp = File(filesDir, "$FILE_NAME.tmp")
    private val lock = Any()

    @Volatile private var cache: MutableMap<String, LibraryMetadataEntry>? = null

    private fun load(): MutableMap<String, LibraryMetadataEntry> {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            if (!filesDir.exists()) filesDir.mkdirs()
            val text = if (target.exists()) target.readText() else ""
            val loaded = LibraryMetadataCodec.fromJson(text).toMutableMap()
            cache = loaded
            return loaded
        }
    }

    fun get(stableKey: String): LibraryMetadataEntry? = load()[stableKey]

    /** Snapshot copy for merging into the row model at load time. */
    fun snapshot(): Map<String, LibraryMetadataEntry> = load().toMap()

    /** Apply [transform] to the current (or empty) entry; empties are dropped. */
    fun update(stableKey: String, transform: (LibraryMetadataEntry) -> LibraryMetadataEntry) {
        synchronized(lock) {
            val map = load()
            val next = transform(map[stableKey] ?: LibraryMetadataEntry())
            if (next.isEmpty()) map.remove(stableKey) else map[stableKey] = next
            writeAtomic(map)
        }
    }

    /** Merged dual-read: canonical entry merged over the legacy alias (if any). */
    fun get(key: RecordingIdentity.MetaKey): LibraryMetadataEntry? {
        val map = load()
        val canonical = map[key.canonical]
        val legacy = key.legacy?.takeIf { it != key.canonical }?.let { map[it] }
        return LibraryMetadataEntry.merge(canonical, legacy)
    }

    /**
     * Merge-on-write: the transform sees the merged (canonical ⊕ legacy) base, the result is written
     * under the canonical key, and the legacy alias — if distinct — is removed (migration complete).
     */
    fun update(key: RecordingIdentity.MetaKey, transform: (LibraryMetadataEntry) -> LibraryMetadataEntry) {
        synchronized(lock) {
            val map = load()
            val canonical = map[key.canonical]
            val legacyKey = key.legacy?.takeIf { it != key.canonical }
            val legacy = legacyKey?.let { map[it] }
            val base = LibraryMetadataEntry.merge(canonical, legacy) ?: LibraryMetadataEntry()
            val next = transform(base)
            if (next.isEmpty()) map.remove(key.canonical) else map[key.canonical] = next
            if (legacyKey != null) map.remove(legacyKey)
            writeAtomic(map)
        }
    }

    /** Drop entries whose key is not in [keep] (deleted/moved-out rows). */
    fun prune(keep: Set<String>) {
        synchronized(lock) {
            val map = load()
            val pruned = LibraryMetadataCodec.prune(map, keep)
            if (pruned.size != map.size) {
                map.clear(); map.putAll(pruned); writeAtomic(map)
            }
        }
    }

    private fun writeAtomic(map: Map<String, LibraryMetadataEntry>) {
        if (!filesDir.exists()) filesDir.mkdirs()
        tmp.writeText(LibraryMetadataCodec.toJson(map))
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) { target.writeText(tmp.readText()); tmp.delete() }
        }
    }

    companion object { const val FILE_NAME = "library_metadata.json" }
}
