package com.aritr.rova.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * Identity tint roles — derive from the active [RovaPalette] and so retint on a theme swap.
 * This is the channel the Liquid Glass theme engine will drive (ADR-0031 §3/§4).
 *
 * [OnAccent] is the content color for a glyph sitting ON a filled-accent surface (the Record
 * FAB disc) — the inverse of [Accent]. It is AA-paired with the (deepened) accent fill via the
 * same resolver as the premium dialog CTA, so white-on-light-accent never fails (owner 2026-06-19).
 */
enum class IconRole { Default, Secondary, Disabled, Accent, OnAccent }

/**
 * Locked status roles — bound to [RovaSemantics], identical across all 12 palettes, never
 * theme-retinted and never per-call alpha-diluted (ADR-0031 §3, WCAG 1.4.1: always paired with shape).
 */
enum class IconStatus { Recovered, Interrupted, Processing, Success, Warning, Rec }

/**
 * The single content-color contract for every in-app glyph (ADR-0031 §4). Pure-Kotlin so it is
 * JVM-unit-testable under `isReturnDefaultValues = true`; the framework-touching part is the thin
 * [com.aritr.rova.ui.components.SemanticIcon] composable. Mirrors the GlassResolver / LibraryColorSpec
 * identity-vs-locked split exactly.
 */
@Immutable
object SemanticIconSpec {
    /** Disabled glyphs sit far below body text; matches the dominant pre-migration over-media value. */
    private const val DISABLED_ALPHA = 0.30f

    fun tint(palette: RovaPalette, role: IconRole): Color = when (role) {
        IconRole.Default -> palette.textHigh
        IconRole.Secondary -> palette.textDim
        IconRole.Disabled -> palette.textHigh.copy(alpha = DISABLED_ALPHA)
        IconRole.Accent -> palette.accent
        IconRole.OnAccent -> onAccent(palette)
    }

    /**
     * Content color for a glyph on the filled-accent FAB disc. Uses [DialogActionColors.resolve]
     * (the premium-CTA AA resolver) so the glyph clears 4.5:1 over the deepened accent gradient on
     * EVERY palette — white on the dark/saturated accents (the board look), near-black only on the
     * few neon-light accents (Meadow) where white can't reach AA. Pure → JVM-testable.
     */
    private fun onAccent(palette: RovaPalette): Color {
        val cta = DialogActionColors.resolve(palette.accent.toRgb255(), palette.accent2.toRgb255())
        // White / pure-black are EXACTLY the two labels DialogActionColors proves clear AA over the
        // deepened fill (its resolver uses WHITE=1.0 / BLACK=0.0). Using the same endpoints keeps the
        // FAB on-accent tint inside that proven envelope — no razor-edge from a near-black.
        return if (cta.contentWhite) Color.White else Color.Black
    }

    private fun Color.toRgb255(): IntArray =
        intArrayOf((red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())

    fun statusTint(status: IconStatus): Color = when (status) {
        IconStatus.Recovered -> RovaSemantics.success
        IconStatus.Interrupted -> RovaSemantics.warning
        IconStatus.Processing -> RovaSemantics.escalating
        IconStatus.Success -> RovaSemantics.success
        IconStatus.Warning -> RovaSemantics.warning
        IconStatus.Rec -> RovaSemantics.rec
    }
}
