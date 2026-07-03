package com.aritr.rova.ui.library

/**
 * Expands row/selection keys to playable-file keys (spec §3.4). An aggregated DualShot session
 * row keys on the canonical session key, which resolves to NO VideoItem — file-level operations
 * (share/delete/play) must fan out to the ORIGINAL per-side keys carried in [LibraryRow.sides].
 * Non-session keys (and keys with no row) pass through untouched. Order-preserving, de-duplicated.
 */
object LibrarySessionKeys {
    fun expand(keys: Collection<String>, rowsByKey: Map<String, LibraryRow>): Set<String> =
        keys.flatMapTo(LinkedHashSet()) { k ->
            val sides = rowsByKey[k]?.sides
            if (sides.isNullOrEmpty()) listOf(k) else sides.map { it.stableKey }
        }
}
