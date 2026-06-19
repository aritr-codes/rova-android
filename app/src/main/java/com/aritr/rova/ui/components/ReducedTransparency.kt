package com.aritr.rova.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Reduced-transparency decision seam (ADR-0028 glass degradation; WCAG 2.2 AA
 * SC 1.4.6 "Contrast (Enhanced)" intent). Decides when [com.aritr.rova.ui.theme.GlassResolver]
 * must drop the semi-transparent glass fills for solid, high-contrast surfaces.
 *
 * Android has **no public "reduce transparency" API** (unlike iOS). The closest
 * real OS signal is the **high-contrast text** accessibility toggle, surfaced
 * through the secure setting `high_text_contrast_enabled`. A user who enables it
 * is explicitly asking for maximum legibility, which is exactly the case where
 * faux-blur glass over busy content must collapse to a solid fill.
 *
 * Until 2026-06-19 `reduceTransparency` was wired to [ReducedMotion] alone — so
 * a user who turned on high-contrast text but NOT remove-animations never got
 * the solid path (the gap this seam closes). We OR the two signals: high-contrast
 * is the real transparency trigger, and reduce-motion is kept as a fallback so
 * existing reduce-motion users see no regression.
 *
 * Pure-helper pattern: [reduceTransparency] is framework-free and unit-tested;
 * [isReduced]/[rememberReduceTransparency] are the thin framework seams.
 */
object ReducedTransparency {

    /**
     * The policy: collapse glass when EITHER the OS high-contrast-text toggle is
     * on OR animations are reduced. High-contrast is the semantically-correct
     * transparency signal; reduce-motion is the no-regression fallback.
     */
    fun reduceTransparency(highContrastText: Boolean, reduceMotion: Boolean): Boolean =
        highContrastText || reduceMotion

    /** Reads the secure high-contrast-text flag (default `false` if unset/unreadable). */
    fun isHighContrastText(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, HIGH_TEXT_CONTRAST_ENABLED, 0) == 1

    /** Resolves the policy from the live OS signals. */
    fun isReduced(context: Context): Boolean =
        reduceTransparency(isHighContrastText(context), ReducedMotion.isReduced(context))

    // Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED is @hide; the key
    // string is stable across releases. Readable without a permission.
    private const val HIGH_TEXT_CONTRAST_ENABLED = "high_text_contrast_enabled"
}

/**
 * Composable accessor for [ReducedTransparency.isReduced]. The underlying OS
 * toggles change only via system settings, so a [remember] keyed on the context
 * is sufficient (matches [rememberReduceMotion]).
 */
@Composable
fun rememberReduceTransparency(): Boolean {
    val context = LocalContext.current
    return remember(context) { ReducedTransparency.isReduced(context) }
}
