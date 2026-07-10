package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * The one per-backing resolver for **a hue used as ink** (Trust System V1, APPX-D).
 *
 * **Design authority:** `docs/design/warnings-recovery.html` v1.0 (DESIGN FROZEN 2026-07-09),
 * `resolveInk` (`:1252`–`:1264`). Ported verbatim; Compose transcribes it and never diverges.
 *
 * A hue used as ink must clear its target ratio against WHATEVER it sits on, and no single fixed
 * derivation can. `color-mix(sev 62%, tHigh)` clears 4.5:1 on the eleven dark palettes and reads
 * 2.85:1 on Daylight, where gold-on-pale-gold is nearly invisible; the same bug lived a second
 * time in `color-mix(accent 72%, tHigh)` (4.24:1 on a tinted chip). Both fixed mixes are rejected.
 *
 * Direction is chosen by the **backing's** luminance ([DARK_BACKING]):
 *
 *  - **dark backing** → the committed lighten derivation, `mix(hue, mix, top)`: [MIX_LABEL] for a
 *    severity label, [MIX_ACCENT] for accent-as-text, [MIX_MARK] (= raw locked hue) for marks.
 *    Zero visual delta versus the shipped surfaces. It deliberately **does not consult [target]** —
 *    that it clears is proven by `TrustContrastSweepTest`, not enforced here.
 *  - **light backing** → [DialogActionColors.deepen] — the same primitive the accent-CTA resolver
 *    uses — at the minimum `t ∈ {0, .12, … .6}` that clears [target]. Hue is preserved on THIS
 *    branch only (proportional RGB scaling); the lighten branch desaturates toward the host ink,
 *    by design. If no `t` clears, [MAX_T] is the floor.
 *
 * Of the twelve palettes only Daylight ever takes the deepen branch, and only at its four themed
 * sites — the four pinned sites keep a near-black backing whatever the theme.
 *
 * Pure and framework-free apart from [Color] (a value class over 8-bit sRGB), so it is unit-testable
 * under `isReturnDefaultValues = true` — the house pure-helper seam, as with [ContrastMath].
 *
 * **Boundary (APPX-C):** this resolver owns hues used as *ink*. Accent *fills* stay on
 * [DialogActionColors.resolve]; severity *fills* never route through either.
 */
object ResolveInk {

    /** Deepen increment on the light-backing branch. */
    const val STEP: Double = 0.12

    /** Deepen ceiling; also the fallback when no step clears the target. */
    const val MAX_T: Double = 0.6

    /** Backings below this relative luminance take the lighten branch. */
    const val DARK_BACKING: Double = 0.18

    /** Lighten mix fraction for a severity hue used as a label. */
    const val MIX_LABEL: Double = 0.62

    /** Lighten mix fraction for the accent hue used as text. */
    const val MIX_ACCENT: Double = 0.72

    /** Marks carry no mix: the raw locked hue survives the lighten branch untouched. */
    const val MIX_MARK: Double = 0.0

    /** SC 1.4.3 — text (labels, accent-as-text). */
    const val TARGET_TEXT: Double = 4.5

    /** SC 1.4.11 — non-text marks (dots, glyphs). */
    const val TARGET_MARK: Double = 3.0

    /** Which way the hue was moved to clear its backing. */
    enum class Direction { LIGHTEN, DEEPEN }

    /**
     * A resolved ink. [t] is the deepen amount actually spent (always `0.0` on [Direction.LIGHTEN]).
     * [color] is 8-bit — Compose quantizes every sRGB [Color] — which is exactly what the surface
     * paints, and what the spec's `rgbToHex(resolveInk(...).rgb)` writes into its stylesheet.
     */
    data class Ink(val color: Color, val t: Double, val direction: Direction)

    /**
     * Resolve [hue] into an ink legible on [backing].
     *
     * @param hue the locked severity colour, or the palette accent
     * @param backing what the ink actually sits on — a chip fill, an icon box, the pinned capsule
     *   over live media, an elevated themed surface. Not the container two layers up.
     * @param target [TARGET_TEXT] or [TARGET_MARK]
     * @param top the host ink the lighten branch mixes toward (the surface's high-emphasis text)
     * @param mix [MIX_LABEL], [MIX_ACCENT], or [MIX_MARK]
     */
    fun of(hue: Color, backing: Color, target: Double, top: Color, mix: Double): Ink {
        val hue255 = hue.channels255()

        val backingLuminance = luminanceOf(backing)
        if (backingLuminance < DARK_BACKING) {
            val lightened = if (mix != MIX_MARK) {
                ContrastMath.compositeAlphaOverExact(
                    hue255[0].toDouble(), hue255[1].toDouble(), hue255[2].toDouble(),
                    mix,
                    top.red * 255.0, top.green * 255.0, top.blue * 255.0,
                )
            } else {
                doubleArrayOf(hue255[0].toDouble(), hue255[1].toDouble(), hue255[2].toDouble())
            }
            return Ink(
                color = Color(
                    (lightened[0] / 255.0).toFloat(),
                    (lightened[1] / 255.0).toFloat(),
                    (lightened[2] / 255.0).toFloat(),
                ),
                t = 0.0,
                direction = Direction.LIGHTEN,
            )
        }

        var t = 0.0
        while (t <= MAX_T + 1e-9) {
            val deepened = DialogActionColors.deepen(hue255, t)
            if (ratioOver(deepened, backingLuminance) >= target) {
                return Ink(Color(deepened[0], deepened[1], deepened[2]), t, Direction.DEEPEN)
            }
            t += STEP
        }
        val floor = DialogActionColors.deepen(hue255, MAX_T)
        return Ink(Color(floor[0], floor[1], floor[2]), MAX_T, Direction.DEEPEN)
    }

    private fun Color.channels255(): IntArray = intArrayOf(
        (red * 255f).roundToInt(),
        (green * 255f).roundToInt(),
        (blue * 255f).roundToInt(),
    )

    private fun luminanceOf(c: Color): Double =
        ContrastMath.relativeLuminance(c.red * 255.0, c.green * 255.0, c.blue * 255.0)

    private fun ratioOver(fg: IntArray, backingLuminance: Double): Double =
        ContrastMath.contrastRatio(
            ContrastMath.relativeLuminance(fg[0], fg[1], fg[2]),
            backingLuminance,
        )
}
