package com.aritr.rova.ui.screens

import com.aritr.rova.ui.components.RecordHudState

/** What the center button in the Record bottom nav shows / does. */
enum class RecordFabState { Start, Stop, Disabled }

/**
 * Pure derivation of the center-FAB state from the HUD state + gating booleans.
 *
 * - Idle + a hard-block active (camera permission denied OR storage insufficient
 *   to start — see [com.aritr.rova.ui.warnings.WarningCenterViewModel] / RecordScreen's
 *   leaf-signal reads) → [Disabled]; the actionable CTA lives in the auto-presented
 *   warning sheet, not the FAB.
 * - Idle, not blocked → [Start].
 * - Recording or Waiting → [Stop] (always — `sessionLocked`/`hardBlockActive` are
 *   irrelevant once a session is running).
 * - Merging → [Disabled] — the merge runs `NonCancellable` (ADR 0006), so a Stop
 *   affordance would be a lie.
 *
 * `sessionLocked` (= isPeriodicActive || isMerging) is passed for symmetry / future
 * use; the FAB is `Stop` (not `Disabled`) during an active session.
 */
fun recordFabState(
    hudState: RecordHudState,
    sessionLocked: Boolean,
    hardBlockActive: Boolean,
): RecordFabState = when (hudState) {
    RecordHudState.Idle -> if (hardBlockActive) RecordFabState.Disabled else RecordFabState.Start
    RecordHudState.Recording, RecordHudState.Waiting -> RecordFabState.Stop
    is RecordHudState.Merging -> RecordFabState.Disabled
}
