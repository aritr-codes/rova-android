package com.aritr.rova.ui.theme

/**
 * Pure builder for the pinned camera/media route glass environment
 * (Record/Player/Onboarding). ADR-0028 §2.4.
 *
 * Invariant (PR2, codex-reviewed 2026-06-08): pinned routes render on the
 * shared [NeutralDarkRecordPalette] surface and take ONLY the active palette's
 * dark-safe accents. A light active theme (e.g. Daylight) must never leak a
 * light glassTint/edge/isLight into a route painted over a live camera.
 */
object PinnedGlassEnvironment {
    fun forPinnedRoute(active: GlassEnvironment): GlassEnvironment =
        active.copy(
            palette = NeutralDarkRecordPalette.copy(
                accentOnDark = active.palette.accentOnDark,
                accentContainerOnDark = active.palette.accentContainerOnDark,
            ),
        )
}
