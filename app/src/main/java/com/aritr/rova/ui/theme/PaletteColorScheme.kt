package com.aritr.rova.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.roundToInt

/**
 * The propagation engine (ADR-0028 amendment 2026-06-18). Pure mapper: the active
 * [RovaPalette] -> a Material3 [ColorScheme] so EVERY `MaterialTheme.colorScheme.*`
 * reader restyles per palette. Built from the pure `darkColorScheme()/lightColorScheme()`
 * factory base + `.copy()` so newer M3 slots keep valid defaults (codex 2026-06-18).
 *
 * Identity-vs-locked (ADR-0028 §1.3, same contract as [LibraryColorSpec]): `error*`
 * come from [RovaSemantics], `scrim` is black — the palette NEVER feeds them. The
 * surfaceContainer family is a NEUTRAL tonal ladder off [RovaPalette.surfaceBase], not
 * accent-tinted. Framework-free -> JVM-tested ([PaletteColorSchemeTest]).
 */
object PaletteColorScheme {

    private val NearBlack = Color(0xFF0B0B0F)

    fun from(p: RovaPalette): ColorScheme {
        val base = if (p.isLight) lightColorScheme() else darkColorScheme()
        val sb = p.surfaceBase

        val (primary, onPrimary) = resolveFill(p.accent)
        val (secondary, onSecondary) = resolveFill(p.accent2)   // tertiary mirrors secondary (no 3rd hue, §8)

        val ladder = surfaceLadder(sb, p.isLight)

        return base.copy(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = p.accentContainerOnDark.compositeOver(sb),
            onPrimaryContainer = p.textHigh,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = p.accent2.copy(alpha = 0.22f).compositeOver(sb),
            onSecondaryContainer = p.textHigh,
            tertiary = secondary,
            onTertiary = onSecondary,
            tertiaryContainer = p.accent2.copy(alpha = 0.22f).compositeOver(sb),
            onTertiaryContainer = p.textHigh,
            background = sb,
            onBackground = p.textHigh,
            surface = sb,
            onSurface = p.textHigh,
            surfaceVariant = ladder.variant,
            onSurfaceVariant = p.textDim,
            surfaceDim = ladder.dim,
            surfaceBright = ladder.bright,
            surfaceContainerLowest = ladder.lowest,
            surfaceContainerLow = ladder.low,
            surfaceContainer = ladder.mid,
            surfaceContainerHigh = ladder.high,
            surfaceContainerHighest = ladder.highest,
            outline = promoteOpaque(p.edge, sb, 0.55f),
            outlineVariant = promoteOpaque(p.edge, sb, 0.30f),
            surfaceTint = primary,
            inverseSurface = if (p.isLight) Color(0xFF1A1C20) else Color(0xFFE7E9EE),
            inverseOnSurface = if (p.isLight) Color(0xFFF2F3F6) else Color(0xFF15171C),
            inversePrimary = p.accent,
            error = RovaSemantics.error,
            // onError is near-black: white on the locked #EF4444 error is only ~3.95:1 (< AA 4.5).
            onError = NearBlack,
            // errorContainer / onErrorContainer keep the M3 factory defaults — the locked
            // RovaSemantics.error must NOT be mutated (.copy) per ADR-0031 §3 / checkStatusColorLocked.
            scrim = Color.Black,
        )
    }

    /** Resolve a SOLID accent slot to (deepened fill, AA label) via the shared CTA helper. */
    private fun resolveFill(accent: Color): Pair<Color, Color> {
        val rgb = accent.toRgb255()
        val cta = DialogActionColors.resolve(rgb, rgb)
        val fill = Color(red = cta.start[0], green = cta.start[1], blue = cta.start[2])
        return fill to if (cta.contentWhite) Color.White else NearBlack
    }

    /** Composite a low-alpha edge at a boosted alpha over the opaque base -> visible opaque outline. */
    private fun promoteOpaque(edge: Color, base: Color, boost: Float): Color =
        edge.copy(alpha = (edge.alpha + boost).coerceAtMost(1f)).compositeOver(base)

    private data class Ladder(
        val dim: Color, val variant: Color, val bright: Color,
        val lowest: Color, val low: Color, val mid: Color, val high: Color, val highest: Color,
    )

    /**
     * Neutral opaque surface ladder off [base]. M3 elevation direction (codex): `surfaceBright`
     * always blends toward WHITE; the surfaceContainer ladder + variant use a direction-correct
     * elevation tint (toward white on dark palettes = more elevated/lighter; toward a neutral
     * darker tone on the light palette). `surfaceDim` is the dimmest. The 5 surfaceContainer rungs
     * share one tint with monotonically increasing alpha (monotonic luminance — see test).
     */
    private fun surfaceLadder(base: Color, isLight: Boolean): Ladder {
        fun toward(tint: Color, a: Float) = tint.copy(alpha = a).compositeOver(base)
        val elevate = if (isLight) Color.Black else Color.White   // container elevation direction
        return Ladder(
            dim = if (isLight) toward(Color.Black, 0.04f) else base,
            variant = toward(elevate, 0.07f),
            bright = toward(Color.White, 0.14f),                  // bright is lighter in BOTH modes
            lowest = toward(elevate, 0.02f),
            low = toward(elevate, 0.04f),
            mid = toward(elevate, 0.06f),
            high = toward(elevate, 0.09f),
            highest = toward(elevate, 0.12f),
        )
    }

    private fun Color.toRgb255(): IntArray = intArrayOf(
        (red * 255).roundToInt().coerceIn(0, 255),
        (green * 255).roundToInt().coerceIn(0, 255),
        (blue * 255).roundToInt().coerceIn(0, 255),
    )
}
