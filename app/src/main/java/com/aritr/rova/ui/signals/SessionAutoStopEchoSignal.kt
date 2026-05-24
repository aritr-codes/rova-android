package com.aritr.rova.ui.signals

import com.aritr.rova.ui.warnings.TerminalEcho
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 4 Slice 2 — leaf signal. Surfaces the most-recent terminal session
 * as a [TerminalEcho], filtered against an in-memory dismissed-id set. The
 * I/O lives behind the [terminalEchoSource] seam (production wires
 * `SessionStore.latestTerminalSession()`); tests inject a pure `() -> TerminalEcho?`.
 *
 * This signal is REASON-AGNOSTIC: it surfaces ALL terminal echoes regardless
 * of stop reason. The `stopReason == LOW_STORAGE` filter is in
 * [com.aritr.rova.ui.warnings.WarningPrecedence.resolve].
 *
 * Dismissal: in-memory only. The factory in `WarningCenter.kt` wires the
 * persistent set on `RovaSettings.dismissedAutoStopEchoIds` and seeds
 * [initialDismissedIds] from it at construction. `markDismissed(id)` calls
 * are paired with a `RovaSettings` write in the factory callback.
 *
 * Refresh triggers: `RecordScreen` `ON_RESUME` lifecycle event, and any
 * out-of-band terminal-transition observer wired in `RovaApp` (see T6).
 *
 * Spec: docs/superpowers/specs/2026-05-24-phase-4-slice2-storage-full-autostopped-design.md §4.4
 */
class SessionAutoStopEchoSignal(
    private val terminalEchoSource: () -> TerminalEcho?,
    initialDismissedIds: Set<String> = emptySet(),
) {
    private val _state = MutableStateFlow<TerminalEcho?>(null)
    val state: StateFlow<TerminalEcho?> = _state.asStateFlow()

    private var dismissed: Set<String> = initialDismissedIds

    init { recompute() }

    /** Re-poll source. Called on RecordScreen ON_RESUME + on service terminal transitions. */
    fun refresh() { recompute() }

    /** Add [sessionId] to the dismissed set and re-filter the flow. */
    fun markDismissed(sessionId: String) {
        dismissed = dismissed + sessionId
        recompute()
    }

    private fun recompute() {
        val latest = terminalEchoSource()
        _state.value = latest?.takeIf { it.sessionId !in dismissed }
    }
}
