package com.aritr.rova.ui.library

/**
 * Session-level sidecar lazy-merge for DualShot rows (spec §3.4, ADR-0030 amendment §3).
 *
 * Pre-#137 sidecar entries were keyed per-side (file path), so a session's two sides can carry
 * divergent legacy metadata. The aggregated session row must read ONE truth. Chained
 * [LibraryMetadataEntry.merge] keeps its proven field semantics with earlier-argument priority
 * (canonical > portrait legacy > landscape legacy): customTitle = first non-null portrait-wins,
 * favorite = either side, lastPlayedAt = max, positions union. READ-path only — writes keep
 * going to the canonical session key ([LibraryMetadataStore.update] merge-on-write).
 */
object SessionSidecarMerge {
    fun resolve(
        canonical: LibraryMetadataEntry?,
        sideLegaciesPortraitFirst: List<LibraryMetadataEntry?>,
    ): LibraryMetadataEntry? =
        sideLegaciesPortraitFirst.fold(canonical) { acc, legacy -> LibraryMetadataEntry.merge(acc, legacy) }
}
