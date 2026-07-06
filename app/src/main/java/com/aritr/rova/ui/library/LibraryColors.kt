package com.aritr.rova.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.aritr.rova.ui.theme.DialogActionColors
import com.aritr.rova.ui.theme.LocalGlassEnvironment
import com.aritr.rova.ui.theme.RovaPalette
import com.aritr.rova.ui.theme.RovaSemantics
import kotlin.math.roundToInt

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
    val accentFill: Color,
    val accentInk: Color,
    /**
     * v3.3 playback-progress hairline over media. Resolves to [accentFill] (themed like the LATEST
     * chip) but is a DISTINCT semantic role: its contract is ≥3:1 against the tile's bottom-scrim
     * composite in every palette (accentFill's own contract is AA vs accentInk) — TokenContrastTest.
     */
    val mediaProgress: Color,
    val fill1: Color,
    val fill2: Color,
    val press: Color,
    val hairline: Color,
    /** Details-sheet hero play-disc CTA gradient (Task 9 — non-degenerate accent→accent2). */
    val heroCtaGradient: Brush,
    /** On-hero-CTA glyph ink (white or near-black, AA-resolved with [heroCtaGradient]). */
    val heroCtaInk: Color,
    /** Details-sheet container background (Task 9 — surfaceHi→surface vertical gradient). */
    val sheetBackground: Brush,
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
            accentFill = LibraryColorSpec.accentFill(palette),
            accentInk = LibraryColorSpec.accentInk(palette),
            mediaProgress = LibraryColorSpec.mediaProgress(palette),
            fill1 = LibraryColorSpec.fill1(palette),
            fill2 = LibraryColorSpec.fill2(palette),
            press = LibraryColorSpec.press(palette),
            hairline = LibraryColorSpec.hairline(palette),
            heroCtaGradient = Brush.linearGradient(LibraryColorSpec.heroCtaColors(palette).toList()),
            heroCtaInk = LibraryColorSpec.heroCtaInk(palette),
            sheetBackground = Brush.verticalGradient(
                colorStops = arrayOf(0f to LibraryColorSpec.sheetSurfaceHi(palette), 0.4f to palette.surfaceBase),
            ),
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

    // ── Bento accent + state layers (bento Task 3, additive) ─────────────
    private fun Color.toRgb(): IntArray = intArrayOf(
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt(),
    )

    /** Near-black label used on a bright (undeepened) accent fill — mirrors RovaDialogs' CTA label. */
    private val ACCENT_INK_DARK: Color = Color(0xFF0E1116)

    /**
     * Bento tile accent = a flat fill, so it reuses the premium-dialog CTA resolver
     * ([DialogActionColors.resolve]) with a degenerate (solid) "gradient" of (accent, accent) — the
     * same AA-guaranteed deepen-or-dark-label strategy the dialog system already proved out.
     */
    fun accentCta(p: RovaPalette): DialogActionColors.Cta {
        val rgb = p.accent.toRgb()
        return DialogActionColors.resolve(rgb, rgb)
    }

    /** Resolved accent fill (possibly deepened for label AA — see [accentCta]). */
    fun accentFill(p: RovaPalette): Color {
        val cta = accentCta(p)
        return Color(cta.start[0], cta.start[1], cta.start[2])
    }

    /** On-accent label ink — white when it clears AA on the resolved fill, else [ACCENT_INK_DARK]. */
    fun accentInk(p: RovaPalette): Color = if (accentCta(p).contentWhite) Color.White else ACCENT_INK_DARK

    /**
     * v3.3 `--media-progress` — the playback-progress hairline paint. Same resolved value as
     * [accentFill] today, but a separate semantic role by design (frozen spec v3.3): the hairline
     * sits over the bottom-scrim composite, so ITS contract is ≥3:1 against that composite in all
     * 12 palettes (verified by TokenContrastTest), not accentFill's fill-vs-ink AA.
     */
    fun mediaProgress(p: RovaPalette): Color = accentFill(p)

    // Frozen state-layer alphas over textHigh (bento spec — not theme-tunable).
    private const val FILL1_ALPHA = 0.05f
    private const val FILL2_ALPHA = 0.08f
    private const val PRESS_ALPHA = 0.12f
    private const val HAIRLINE_ALPHA = 0.06f

    fun fill1(p: RovaPalette): Color = p.textHigh.copy(alpha = FILL1_ALPHA)
    fun fill2(p: RovaPalette): Color = p.textHigh.copy(alpha = FILL2_ALPHA)
    fun press(p: RovaPalette): Color = p.textHigh.copy(alpha = PRESS_ALPHA)
    fun hairline(p: RovaPalette): Color = p.textHigh.copy(alpha = HAIRLINE_ALPHA)

    // ── Task 9: details-sheet hero CTA + container background ────────────
    /**
     * Hero play-disc gradient (frozen sheet spec) — unlike [accentCta]'s degenerate solid fill,
     * this resolves the REAL two-stop accent gradient ([RovaPalette.accent] → [accent2]) through
     * the same AA-guaranteed [DialogActionColors.resolve] strategy (mirrors the mockup's ctaResolve).
     */
    fun heroCta(p: RovaPalette): DialogActionColors.Cta =
        DialogActionColors.resolve(p.accent.toRgb(), p.accent2.toRgb())

    /** Hero CTA gradient endpoints as Compose [Color]s (start, end). */
    fun heroCtaColors(p: RovaPalette): Pair<Color, Color> {
        val cta = heroCta(p)
        return Color(cta.start[0], cta.start[1], cta.start[2]) to Color(cta.end[0], cta.end[1], cta.end[2])
    }

    /** On-hero-CTA glyph ink — white when it clears AA on the gradient, else [ACCENT_INK_DARK]. */
    fun heroCtaInk(p: RovaPalette): Color = if (heroCta(p).contentWhite) Color.White else ACCENT_INK_DARK

    /** Sheet-hi background stop: `surfaceBase` lightened 8% toward white (mirrors the mockup's `--surface-hi`). */
    fun sheetSurfaceHi(p: RovaPalette): Color = lerp(p.surfaceBase, Color.White, 0.08f)
}
