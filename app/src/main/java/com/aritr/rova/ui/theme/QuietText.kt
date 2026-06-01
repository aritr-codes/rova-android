package com.aritr.rova.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * B2 — pure decision for "quiet" (secondary / label) text color.
 *
 * On a DARK scheme the app dims `onSurfaceVariant` with a low alpha for the
 * intentionally-quiet aesthetic. That alpha-dimming is mathematically below
 * WCAG 2.2 AA (SC 1.4.3) over a LIGHT background (at 0.45α even pure black is
 * only 3:1), so on light we return the solid color, which passes AA. See
 * docs/superpowers/specs/2026-05-31-b2-theme-switcher-design.md §5 + ADR-0020.
 */
fun quietTextColor(isDark: Boolean, onSurfaceVariant: Color, dimAlpha: Float): Color =
    if (isDark) onSurfaceVariant.copy(alpha = dimAlpha) else onSurfaceVariant

/**
 * Composable wrapper: reads the active scheme and routes [quietTextColor].
 * "Dark" is detected from the surface luminance so it also resolves correctly
 * inside a [RovaDarkSurface] island, not only under the top-level [RovaTheme].
 */
@Composable
fun rovaQuietText(dimAlpha: Float): Color {
    val cs = MaterialTheme.colorScheme
    return quietTextColor(
        isDark = cs.surface.luminance() < 0.5f,
        onSurfaceVariant = cs.onSurfaceVariant,
        dimAlpha = dimAlpha,
    )
}
