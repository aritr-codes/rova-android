package com.aritr.rova.ui.theme

import kotlin.math.roundToInt

/**
 * Pure WCAG-checked color resolution for the premium filled dialog CTA (RovaAlertDialog, owner
 * 2026-06-17 "make it premium"). The CTA is a horizontal accent gradient ([RovaPalette.accent] →
 * [RovaPalette.accent2]); its label must clear the 4.5:1 AA bar (SC 1.4.3, ADR-0020) over EVERY
 * sample of that gradient — not just the start color. Accents span royal-violet (dark) to neon
 * Meadow green #9AE65C (light), so a fixed white label would fail on the light ones.
 *
 * Strategy (codex 2026-06-17): PREFER a white label (the conventional premium look on a saturated
 * accent pill). Deepen the fill toward black in small steps until white clears AA. If white clears
 * within a modest deepen budget ([WHITE_DEEPEN_LIMIT]) → white on the (slightly deepened) accent — e.g.
 * pastel royal-violet becomes a richer violet with a white label. If the accent is SO light that white
 * would need heavy deepening (neon Meadow green), don't murk the brand color: keep the bright fill and
 * use a near-black label (which clears AA on light fills). Framework-free → JVM-testable, same seam as
 * [ContrastMath] / [AspectFitMath].
 */
object DialogActionColors {

    private const val AA = 4.5
    private const val MAX_DEEPEN = 0.6
    private const val STEP = 0.12

    /** Max deepen we'll spend to keep a white label before falling back to a dark label on the bright fill. */
    private const val WHITE_DEEPEN_LIMIT = 0.4

    /** Resolved CTA paint: gradient endpoints (sRGB 0..255) + whether the label is white. */
    data class Cta(val start: IntArray, val end: IntArray, val contentWhite: Boolean) {
        override fun equals(other: Any?): Boolean =
            other is Cta && start.contentEquals(other.start) &&
                end.contentEquals(other.end) && contentWhite == other.contentWhite

        override fun hashCode(): Int =
            (start.contentHashCode() * 31 + end.contentHashCode()) * 31 + contentWhite.hashCode()
    }

    private fun lum(c: IntArray): Double = ContrastMath.relativeLuminance(c[0], c[1], c[2])

    private fun mid(a: IntArray, b: IntArray): IntArray =
        intArrayOf((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2)

    /**
     * Scale a color toward black by [t] (0 = unchanged, 1 = black).
     *
     * `internal`, not private: [ResolveInk]'s light-backing branch deepens a severity hue with
     * *this* primitive rather than a second copy of it (parity plan P2 — one resolver, one
     * deepen). Visibility only; the function is unchanged and [DialogActionColors] remains the
     * sole owner of accent-*fill* resolution (APPX-C).
     */
    internal fun deepen(c: IntArray, t: Double): IntArray = intArrayOf(
        (c[0] * (1 - t)).roundToInt().coerceIn(0, 255),
        (c[1] * (1 - t)).roundToInt().coerceIn(0, 255),
        (c[2] * (1 - t)).roundToInt().coerceIn(0, 255),
    )

    /** Worst-case (minimum) contrast of a foreground luminance over the 3 gradient samples. */
    private fun worst(fgLum: Double, start: IntArray, end: IntArray): Double {
        val samples = listOf(start, mid(start, end), end)
        return samples.minOf { ContrastMath.contrastRatio(fgLum, lum(it)) }
    }

    private val WHITE = 1.0
    private val BLACK = 0.0

    fun resolve(start: IntArray, end: IntArray): Cta {
        // Prefer a white label: deepen until white clears AA, spending at most WHITE_DEEPEN_LIMIT.
        var t = 0.0
        while (t <= MAX_DEEPEN + 1e-9) {
            val ds = deepen(start, t)
            val de = deepen(end, t)
            if (worst(WHITE, ds, de) >= AA) {
                if (t <= WHITE_DEEPEN_LIMIT + 1e-9) return Cta(ds, de, true)
                break // accent too light — white needs heavy deepening; keep the bright fill instead.
            }
            t += STEP
        }
        // Too light for an affordable white label → near-black label on the original bright fill.
        if (worst(BLACK, start, end) >= AA) return Cta(start, end, false)
        // Last resort (mid-tone that satisfies neither cheaply) — deepest fill, white label.
        val ds = deepen(start, MAX_DEEPEN)
        val de = deepen(end, MAX_DEEPEN)
        return Cta(ds, de, true)
    }
}
