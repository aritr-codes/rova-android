package com.aritr.rova.ui.library

/**
 * Immutable multi-select state over row [keys] (stableKey strings). [active] is the select-mode flag;
 * it auto-clears whenever the selection empties so the UI drops back to the normal top bar. Pure — no
 * Android/Compose types (spec §5.6, JVM-tested).
 */
data class SelectionState(
    val active: Boolean = false,
    val keys: Set<String> = emptySet(),
) {
    val count: Int get() = keys.size
}

/** Pure transitions for [SelectionState]. Every function returns a new state. */
object SelectionReducer {

    /** Long-press entry: activate select mode with [key] selected. */
    fun enter(state: SelectionState, key: String): SelectionState =
        SelectionState(active = true, keys = state.keys + key)

    /** Tap-toggle a key; emptying the selection exits select mode. */
    fun toggle(state: SelectionState, key: String): SelectionState =
        finalize(if (key in state.keys) state.keys - key else state.keys + key)

    /**
     * Per-day select-all: if every [groupKeys] is already selected, deselect them (toggle-off);
     * otherwise union them in.
     */
    fun selectAll(state: SelectionState, groupKeys: List<String>): SelectionState =
        if (groupKeys.isNotEmpty() && state.keys.containsAll(groupKeys)) {
            finalize(state.keys - groupKeys.toSet())
        } else {
            finalize(state.keys + groupKeys)
        }

    /** Drop keys no longer present in [liveKeys] (items list changed). */
    fun reconcile(state: SelectionState, liveKeys: Set<String>): SelectionState =
        if (!state.active) state else finalize(state.keys intersect liveKeys)

    /** Remove a specific set (e.g. rows entering pending-delete). */
    fun removeAll(state: SelectionState, drop: Set<String>): SelectionState =
        finalize(state.keys - drop)

    /** Hard reset to inactive/empty. */
    fun clear(@Suppress("UNUSED_PARAMETER") state: SelectionState): SelectionState = SelectionState()

    private fun finalize(keys: Set<String>): SelectionState =
        if (keys.isEmpty()) SelectionState() else SelectionState(active = true, keys = keys)
}
