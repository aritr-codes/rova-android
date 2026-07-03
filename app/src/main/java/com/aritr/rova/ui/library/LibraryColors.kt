package com.aritr.rova.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.aritr.rova.ui.theme.ContrastMath
import com.aritr.rova.ui.theme.LocalGlassEnvironment
import com.aritr.rova.ui.theme.RovaPalette
import com.aritr.rova.ui.theme.RovaSemantics

/**
 * Theme Foundation (M1–M3, 2026-06-16) — the SINGLE entry point for Library colour identity. No Library
 * component owns its own colour any more: they read these slots so a theme swap restyles the whole screen
 * from one layer (the active [RovaPalette] in [LocalGlassEnvironment]).
 *
 * Two colour families, deliberately split:
 *  - **Identity** (retint per theme): card / hero / chip edges derive from [RovaPalette.edge]. A new tint
 *    pack changes them for free.
 *  - **Overlay-over-media** (LOCKED, theme-independent): scrims, overlay-pill fill/text, the selection ring,
 *    and the on-media glyph backings sit over an ARBITRARY video frame and carry a white-text contrast
 *    contract (the scrim/text values come from [CaptionScrim], proven AA by `CaptionScrimTest`/`ContrastMath`).
 *    Retinting them per theme would break that guarantee, so — exactly like [RovaSemantics] — they are locked.
 *    Status dots map to [RovaSemantics] (state colour is meaning, not theme).
 *
 * Pure derivation lives in [LibraryColorSpec] (framework-free → JVM-testable); this accessor only reads the
 * ambient environment and wraps the locked scrim into a [Brush].
 */
@Immutable
data class LibraryColors(
    val cardEdge: Color,
    val heroEdge: Color,
    val chipEdge: Color,
    val selectionRing: Color,
    val overlayScrim: Color,
    val overlayText: Color,
    val playGlyphScrim: Color,
    val checkChipScrim: Color,
    val heroScrim: Brush,
    val latestContainer: Color,
    val latestEdge: Color,
    val latestEyebrow: Color,
)

/** Reads the active palette from [LocalGlassEnvironment] and resolves the Library colour slots. */
@Composable
fun rememberLibraryColors(): LibraryColors {
    val palette = LocalGlassEnvironment.current.palette
    return remember(palette) {
        val edge = LibraryColorSpec.identityEdge(palette)
        LibraryColors(
            cardEdge = edge,
            heroEdge = edge,
            chipEdge = edge,
            selectionRing = LibraryColorSpec.SELECTION_RING,
            overlayScrim = LibraryColorSpec.OVERLAY_SCRIM,
            overlayText = LibraryColorSpec.OVERLAY_TEXT,
            playGlyphScrim = LibraryColorSpec.PLAY_GLYPH_SCRIM,
            checkChipScrim = LibraryColorSpec.CHECK_CHIP_SCRIM,
            heroScrim = Brush.verticalGradient(listOf(Color.Transparent, LibraryColorSpec.OVERLAY_SCRIM)),
            latestContainer = LibraryColorSpec.latestContainer(palette),
            latestEdge = LibraryColorSpec.latestEdge(palette),
            latestEyebrow = LibraryColorSpec.latestEyebrow(palette),
        )
    }
}

/**
 * Pure colour-derivation seam (house pure-helper pattern). The framework-free half of [LibraryColors] so the
 * identity-vs-locked contract is unit-tested under `isReturnDefaultValues = true`.
 */
object LibraryColorSpec {
    // ── Identity (retints with the theme) ────────────────────────────────
    /** Card / hero / chip hairline edge = the active palette's edge (was a hardcoded Color.White@0.07). */
    fun identityEdge(p: RovaPalette): Color = p.edge

    // ── Locked overlay-over-media (theme-independent; CaptionScrim is the AA source) ──
    /** Pill / hero-scrim peak colour — black @ 0.62, the AA-proven [CaptionScrim] value. */
    val OVERLAY_SCRIM: Color = Color(
        red = CaptionScrim.SCRIM_R / 255f,
        green = CaptionScrim.SCRIM_G / 255f,
        blue = CaptionScrim.SCRIM_B / 255f,
        alpha = CaptionScrim.SCRIM_ALPHA.toFloat(),
    )

    /** White overlay/caption text — the [CaptionScrim] foreground. */
    val OVERLAY_TEXT: Color = Color(
        red = CaptionScrim.TEXT_R / 255f,
        green = CaptionScrim.TEXT_G / 255f,
        blue = CaptionScrim.TEXT_B / 255f,
        alpha = 1f,
    )

    /** Selection ring — locked neutral (owner decision 2026-06-16: identical look + a11y-safe, not retinted). */
    val SELECTION_RING: Color = Color.White.copy(alpha = 0.30f)

    /** Centered static-poster play glyph backing (over media). */
    val PLAY_GLYPH_SCRIM: Color = Color.Black.copy(alpha = 0.34f)

    /** Grid selection check-chip backing (over media). */
    val CHECK_CHIP_SCRIM: Color = Color.Black.copy(alpha = 0.32f)

    // ── Latest-row accent (spec §3.3 — identity family, retints with the theme) ──
    /** Tint alpha of the latest-row container laid over the glass card. */
    private const val LATEST_TINT_ALPHA = 0.10f

    /** Translucent identity tint layered over the latest row's glass card. */
    fun latestContainer(p: RovaPalette): Color = p.accent.copy(alpha = LATEST_TINT_ALPHA)

    /** Hairline accent border of the latest row. */
    fun latestEdge(p: RovaPalette): Color = p.accent.copy(alpha = 0.45f)

    /** Worst-case opaque background the eyebrow sits on (tint composited on the base surface). */
    fun latestContainerOver(p: RovaPalette): Color = latestContainer(p).compositeOver(p.surfaceBase)

    /**
     * "Latest" eyebrow colour — accent-family candidate with an AA fallback: when the palette's
     * accent can't reach 4.5:1 over the tinted container (labelSmall = small text), fall back to
     * textHigh so the eyebrow NEVER ships below AA (spec §3.3; LibraryLatestColorsTest ×12).
     */
    fun latestEyebrow(p: RovaPalette): Color {
        val bg = latestContainerOver(p)
        val candidate = if (p.isLight) p.accent else p.accentOnDark
        return if (contrastOver(candidate, bg) >= 4.5) candidate else p.textHigh
    }

    /** WCAG ratio of [fg] composited over the opaque [bg] (pure — ContrastMath substrate). */
    fun contrastOver(fg: Color, bg: Color): Double {
        val c = fg.compositeOver(bg)
        fun lum(x: Color) = ContrastMath.relativeLuminance(
            (x.red * 255).toInt(), (x.green * 255).toInt(), (x.blue * 255).toInt(),
        )
        return ContrastMath.contrastRatio(lum(c), lum(bg))
    }
}
