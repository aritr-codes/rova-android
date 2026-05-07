package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color

val Sand10 = Color(0xFFFFFBF6)
val Sand30 = Color(0xFFF6F0E7)
val Sand60 = Color(0xFFE9DECF)
val Sand90 = Color(0xFFC7B8A4)

val Ink10 = Color(0xFFF5F7FA)
val Ink30 = Color(0xFFD8E0E8)
val Ink60 = Color(0xFF8FA0B0)
val Ink80 = Color(0xFF4C6175)
val Ink95 = Color(0xFF172533)

val Harbor40 = Color(0xFF29516E)
val Harbor90 = Color(0xFFD1E5F4)

val Copper40 = Color(0xFF97582E)
val Copper90 = Color(0xFFF5DFC8)

val Sage40 = Color(0xFF4F6E62)
val Sage90 = Color(0xFFD2E8DE)

val RecordingRed = Color(0xFFC64133)
val RecordingRedContainer = Color(0xFFFFDAD4)
val OnRecordingRedContainer = Color(0xFF410704)

// Phase 2.1A — dark-scheme palette aligned with docs/UI_DESIGN_TOKENS.md §2.1.
// Replaces the previous near-black stack with the mockup-source page background
// (#06090f), a surface family derived from the frosted sheet color
// (rgba(9,13,20,0.97) over #06090f ≈ #0E1216), and a slightly lighter
// surfaceVariant (#161B23) for the frosted-pill / divider stack.
//
// MidnightSurface is now dark-only — the light scheme `inverseSurface` slot
// references the separate `LightInverseSurface` constant below so its hex
// stays byte-identical with shipped Slices 1-4.
val Midnight = Color(0xFF06090F)
val MidnightSurface = Color(0xFF0E1216)
val MidnightSurfaceAlt = Color(0xFF161B23)
val MidnightOutline = Color(0xFF627181)

// Phase 2.1A — dark-scheme accents from docs/UI_DESIGN_TOKENS.md §2.1.
// InfraBlue replaces Harbor90 as `colorScheme.primary` in dark only;
// SignalRed replaces RecordingRed as `colorScheme.error` in dark only.
// Light scheme keeps Harbor40 / RecordingRed (legibility on light surfaces).
val InfraBlue = Color(0xFF5B7FFF)
val SignalRed = Color(0xFFEF4444)

// Phase 2.1A — pinned to the pre-2.1A MidnightSurface hex so the light
// scheme `inverseSurface` slot is byte-identical with shipped Slices 1-4.
// MidnightSurface itself moved to #0E1216 for the dark surface family;
// the light scheme inverse keeps the older near-black so light-on-dark
// inverse callouts (snackbars, banners) do not visually drift.
val LightInverseSurface = Color(0xFF18212B)
