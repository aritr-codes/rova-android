package com.aritr.rova.ui.library

/**
 * Session-level sidecar metadata for one recording. favorite/customTitle/lastPlayedAt are
 * session-level; playback position is per-side (DualShot P+L has two independent streams), carried
 * in [positionsBySide] keyed by RecordingIdentity.sideSlot ("" = single).
 */
data class LibraryMetadataEntry(
    val favorite: Boolean = false,
    val customTitle: String? = null,
    val lastPlayedAt: Long? = null,
    val positionsBySide: Map<String, Long> = emptyMap(),
) {
    fun isEmpty(): Boolean =
        !favorite && customTitle == null && lastPlayedAt == null && positionsBySide.isEmpty()

    /** Saved position for a side slot; a P+L side with no own value falls back to single "". */
    fun positionFor(slot: String): Long? =
        positionsBySide[slot] ?: if (slot.isNotEmpty()) positionsBySide[""] else null

    /** Returns a copy with [slot] set to [positionMs], or the slot dropped when non-positive. */
    fun withPosition(slot: String, positionMs: Long): LibraryMetadataEntry {
        val next = positionsBySide.toMutableMap()
        if (positionMs > 0L) next[slot] = positionMs else next.remove(slot)
        return copy(positionsBySide = next)
    }

    companion object {
        /**
         * Merge a canonical (session-key) entry with a legacy (path-key) one. Canonical wins per
         * set field, but data is never lost: favorite OR-merges (never un-favorites), lastPlayedAt
         * takes the max (recency independent of which key won migration — codex), positions union
         * with canonical per-side winning.
         */
        fun merge(canonical: LibraryMetadataEntry?, legacy: LibraryMetadataEntry?): LibraryMetadataEntry? {
            if (canonical == null) return legacy
            if (legacy == null) return canonical
            val positions = LinkedHashMap<String, Long>(legacy.positionsBySide)
            positions.putAll(canonical.positionsBySide)
            return LibraryMetadataEntry(
                favorite = canonical.favorite || legacy.favorite,
                customTitle = canonical.customTitle ?: legacy.customTitle,
                lastPlayedAt = maxOfNonNull(canonical.lastPlayedAt, legacy.lastPlayedAt),
                positionsBySide = positions,
            )
        }

        private fun maxOfNonNull(a: Long?, b: Long?): Long? =
            when {
                a == null -> b
                b == null -> a
                else -> maxOf(a, b)
            }
    }
}
