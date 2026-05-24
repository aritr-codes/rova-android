package com.aritr.rova.ui.warnings

import com.aritr.rova.data.StopReason

/**
 * Phase 4 Slice 2 — value type for the auto-stop echo signal. Tiny immutable
 * carrier; passed through `SessionAutoStopEchoSignal.state` and consumed by
 * `WarningPrecedence.resolve` (which filters to `stopReason == LOW_STORAGE`).
 * Spec: docs/superpowers/specs/2026-05-24-phase-4-slice2-storage-full-autostopped-design.md §4.3
 */
data class TerminalEcho(
    val sessionId: String,
    val stopReason: StopReason,
)
