package com.aritr.rova.ui.screens

import com.aritr.rova.ui.components.RecordHudState

/**
 * Pure visual+action spec for the Record FAB (UI Phase 2, PR-2 / board-3 FAB
 * lifecycle). Decouples *what the FAB looks like* from *what tapping it does*,
 * so the renderer ([RecordFab]) stays a thin composable over framework-free
 * descriptors and the whole mapping is JVM-unit-testable.
 *
 * Board FB lifecycle (board-3-semantic.html `FB` row) — matched exactly:
 * - Idle · Record   → accent-gradient disc + `rec_disc` mark (AA on-accent tint).
 * - Recording · Stop→ RED-gradient disc + `rec_morph` white rounded-square.
 * - Disabled        → ghost (translucent accent + edge) + `rec_ring` (dim).
 * - Waiting         → ghost + `waiting` hourglass (accent); still cancellable (Stop).
 * - Processing      → ghost + `proc_arc` spinning arc (accent); merge is NonCancellable
 *                     (ADR-0006), so the FAB is inert here, not a lie.
 */
enum class RecordFabState { Idle, Recording, Waiting, Processing, Disabled }

/**
 * Container background descriptor (board FB `kind`/color), resolved to brushes in [RecordFab]:
 * - [AccentDisc] accent→accent2 gradient (deepened AA via DialogActionColors);
 * - [RedDisc] fixed recording-red gradient;
 * - [Ghost] translucent accent + 1dp edge, no shadow.
 */
enum class FabContainer { AccentDisc, RedDisc, Ghost }

/** Inner mark — the board glyph for the state. [RecMorph] is a white rounded-square Box, not a glyph. */
enum class FabGlyph { RecDisc, RecMorph, RecRing, Waiting, ProcArc }

/** What a tap does. Decoupled from the visual so Waiting (Stop) and Processing (inert) differ. */
enum class FabAction { Start, Stop, None }

/** A11y label kind — mapped to a string resource in [RecordFab] (keeps this file pure). */
enum class FabLabel { Start, Stop, Waiting, Processing, Unavailable }

/** Immutable visual+action descriptor for one FAB state. */
data class RecordFabVisual(
    val container: FabContainer,
    val glyph: FabGlyph,
    val action: FabAction,
    val enabled: Boolean,
    val label: FabLabel,
)

object RecordFabVisualSpec {

    /**
     * Derive the FAB state from the HUD state + the idle hard-block gate.
     * `Recording`+`Starting` collapse to [RecordFabState.Recording] (active clip
     * or pre-first-clip grace); `Waiting` and `Merging` are now DISTINCT states
     * (previously Waiting→Stop and Merging→Disabled made them indistinguishable).
     */
    fun stateFor(hudState: RecordHudState, hardBlockActive: Boolean): RecordFabState =
        when (hudState) {
            RecordHudState.Idle -> if (hardBlockActive) RecordFabState.Disabled else RecordFabState.Idle
            RecordHudState.Recording, RecordHudState.Starting -> RecordFabState.Recording
            RecordHudState.Waiting -> RecordFabState.Waiting
            is RecordHudState.Merging -> RecordFabState.Processing
        }

    fun visualFor(state: RecordFabState): RecordFabVisual = when (state) {
        RecordFabState.Idle -> RecordFabVisual(
            FabContainer.AccentDisc, FabGlyph.RecDisc, FabAction.Start, enabled = true, FabLabel.Start,
        )
        RecordFabState.Recording -> RecordFabVisual(
            FabContainer.RedDisc, FabGlyph.RecMorph, FabAction.Stop, enabled = true, FabLabel.Stop,
        )
        RecordFabState.Waiting -> RecordFabVisual(
            FabContainer.Ghost, FabGlyph.Waiting, FabAction.Stop, enabled = true, FabLabel.Waiting,
        )
        RecordFabState.Processing -> RecordFabVisual(
            FabContainer.Ghost, FabGlyph.ProcArc, FabAction.None, enabled = false, FabLabel.Processing,
        )
        RecordFabState.Disabled -> RecordFabVisual(
            FabContainer.Ghost, FabGlyph.RecRing, FabAction.None, enabled = false, FabLabel.Unavailable,
        )
    }
}
