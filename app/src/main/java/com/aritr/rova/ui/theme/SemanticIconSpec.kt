package com.aritr.rova.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Identity tint roles — derive from the active [RovaPalette] and so retint on a theme swap.
 * This is the channel the Liquid Glass theme engine will drive (ADR-0031 §3/§4).
 */
enum class IconRole { Default, Secondary, Disabled, Accent }

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
    }

    fun statusTint(status: IconStatus): Color = when (status) {
        IconStatus.Recovered -> RovaSemantics.success
        IconStatus.Interrupted -> RovaSemantics.warning
        IconStatus.Processing -> RovaSemantics.escalating
        IconStatus.Success -> RovaSemantics.success
        IconStatus.Warning -> RovaSemantics.warning
        IconStatus.Rec -> RovaSemantics.rec
    }
}
