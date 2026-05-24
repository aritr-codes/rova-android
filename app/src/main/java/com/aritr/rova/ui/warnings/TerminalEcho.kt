package com.aritr.rova.ui.warnings

import com.aritr.rova.data.StopReason

/**
 * Phase 4 Slice 2 — value type for the auto-stop echo signal. Tiny immutable
 * carrier; passed through `SessionAutoStopEchoSignal.state` and consumed by
 * `WarningPrecedence.resolve`'s when-arm over `stopReason`:
 *   - `LOW_STORAGE` → `STORAGE_FULL_AUTOSTOPPED` (Slice 2)
 *   - `THERMAL`     → `THERMAL_AUTOSTOPPED`     (Slice 3, ADR-0016)
 * Other `StopReason` values intentionally yield no echo banner.
 * Spec: docs/superpowers/specs/2026-05-24-phase-4-slice2-storage-full-autostopped-design.md §4.3
 */
data class TerminalEcho(
    val sessionId: String,
    val stopReason: StopReason,
)
