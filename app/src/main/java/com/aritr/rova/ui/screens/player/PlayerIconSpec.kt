package com.aritr.rova.ui.screens.player

import androidx.compose.ui.graphics.vector.ImageVector
import com.aritr.rova.ui.theme.RovaIcons

/**
 * Pure state→glyph choices for the Player transport (ADR-0031, UI Phase 2 PR-5).
 * Kept Compose/Android-free so the decision is JVM-unit-testable under `isReturnDefaultValues`,
 * mirroring [com.aritr.rova.ui.library.components.LibraryIconSpec.favoriteGlyph].
 *
 * The toggle must return a single type: board `pause` is a two-layer [RovaGlyph] (mono, no accent)
 * while board `play` is a mono [ImageVector], so both are exposed as their [ImageVector] face here —
 * the play/pause marks are filled-transport convention (board E1), never duotone.
 */
internal object PlayerIconSpec {

    /** Filled pause while playing, filled play while paused (board E1 "Play / Pause"). */
    fun transportGlyph(isPlaying: Boolean): ImageVector =
        if (isPlaying) RovaIcons.Pause.outline else RovaIcons.Play.glyph
}
