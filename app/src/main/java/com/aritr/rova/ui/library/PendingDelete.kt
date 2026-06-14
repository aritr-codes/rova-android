package com.aritr.rova.ui.library

/**
 * Deferred-delete overlay (ADR-0030 Slice 3, owner + codex). Holds the row keys hidden behind an active
 * Snackbar-UNDO window. The real delete is NOT yet performed — the screen filters these rows out of the
 * rendered list via [visible]; the owning coroutine commits `deleteItems` only on snackbar timeout/swipe,
 * cancels on UNDO, and abandons (clears) on screen dispose. Pure — JVM-tested.
 */
data class PendingDelete(val keys: Set<String> = emptySet()) {
    val isEmpty: Boolean get() = keys.isEmpty()

    fun isPending(key: String): Boolean = key in keys

    /** Rows the UI should render (pending-delete rows hidden). */
    fun visible(rows: List<LibraryRow>): List<LibraryRow> =
        if (keys.isEmpty()) rows else rows.filter { it.stableKey !in keys }

    /**
     * Un-hide rows whose delete failed (they stay in the library); successfully-deleted keys stay hidden
     * until the VM refresh drops them.
     */
    fun restore(failedKeys: Set<String>): PendingDelete = PendingDelete(keys - failedKeys)

    companion object { val NONE = PendingDelete() }
}
