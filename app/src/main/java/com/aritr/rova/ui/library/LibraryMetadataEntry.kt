package com.aritr.rova.ui.library

/**
 * ADR-0030 — one Library sidecar record, keyed externally by row `stableKey`.
 * Lives in [LibraryMetadataStore], never in SessionManifest.
 *
 * @property favorite user-pinned star.
 * @property customTitle user rename; when null the UI shows the [SmartTitle] derived label.
 * @property lastPlayedAt reserved (owner #3): written best-effort on player open; not surfaced in v1 UI.
 * @property positionMs resume point — saved playback position in ms; null/≤0 means "start from beginning".
 */
data class LibraryMetadataEntry(
    val favorite: Boolean = false,
    val customTitle: String? = null,
    val lastPlayedAt: Long? = null,
    val positionMs: Long? = null,
) {
    /** True when this entry holds nothing worth persisting (lets the store prune empty rows). */
    fun isEmpty(): Boolean = !favorite && customTitle == null && lastPlayedAt == null && positionMs == null
}
