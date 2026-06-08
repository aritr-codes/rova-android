package com.aritr.rova.ui.theme

/**
 * Pure builder for the pinned camera/media route glass environment
 * (Record/Player/Onboarding). ADR-0028 §2.4.
 *
 * Invariant (PR2, 2026-06-08): pinned routes render on the shared
 * [NeutralDarkRecordPalette] surface and take ONLY the active palette's accent
 * colors — the `accent`/`accent2` gradient stops (the selected-mode chip's
 * personality, ADR-0028 PR2 §d) plus the dark-safe `accentOnDark`/
 * `accentContainerOnDark` companions. A light active theme (e.g. Daylight) must
 * never leak a light glassTint/edge/isLight into a route painted over a live
 * camera; only its accents carry through.
 */
object PinnedGlassEnvironment {
    fun forPinnedRoute(active: GlassEnvironment): GlassEnvironment =
        active.copy(
            palette = NeutralDarkRecordPalette.copy(
                accent = active.palette.accent,
                accent2 = active.palette.accent2,
                accentOnDark = active.palette.accentOnDark,
                accentContainerOnDark = active.palette.accentContainerOnDark,
            ),
        )
}
