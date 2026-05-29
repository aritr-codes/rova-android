package com.aritr.rova.ui.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure-Kotlin WCAG 2.1 contrast math (relative luminance + contrast ratio +
 * alpha compositing). Framework-free so it is unit-testable under
 * `isReturnDefaultValues = true` — same seam pattern as [AspectFitMath].
 *
 * Introduced for the WCAG 2.2 AA remediation effort (ADR-0020; audit
 * `docs/accessibility/2026-05-29-wcag-2.2-aa-audit.md`). It is the reusable
 * basis for verifying token/text contrast and the seed for the future
 * `checkA11yNoLowAlphaTextToken` static gate sketched in ADR-0020.
 *
 * Channels are sRGB integers in `0..255`. The 4.5:1 / 3:1 AA bars
 * (SC 1.4.3 / 1.4.11) are the caller's to apply against [contrastRatio].
 */
object ContrastMath {

    /** sRGB 8-bit channel → linearized component per WCAG 2.1. */
    private fun linearize(channel: Int): Double {
        val c = channel.coerceIn(0, 255) / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** Relative luminance (0.0 = black … 1.0 = white) per WCAG 2.1. */
    fun relativeLuminance(r: Int, g: Int, b: Int): Double =
        0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)

    /** Contrast ratio of two relative luminances; symmetric, range 1.0..21.0. */
    fun contrastRatio(lum1: Double, lum2: Double): Double {
        val lighter = max(lum1, lum2)
        val darker = min(lum1, lum2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Source-over composite of an opaque foreground at [alpha] (0.0..1.0)
     * onto an opaque background. Returns the composited `[r, g, b]` in 0..255.
     * Both layers are treated as opaque (the background is assumed to be the
     * already-resolved surface the text sits on).
     */
    fun compositeAlphaOver(
        fr: Int, fg: Int, fb: Int,
        alpha: Double,
        br: Int, bg: Int, bb: Int,
    ): IntArray {
        val a = alpha.coerceIn(0.0, 1.0)
        fun mix(f: Int, b: Int) = (f * a + b * (1 - a)).roundToInt().coerceIn(0, 255)
        return intArrayOf(mix(fr, br), mix(fg, bg), mix(fb, bb))
    }

    /**
     * Contrast ratio of a foreground at [alpha] composited over an opaque
     * background — the common "is this low-alpha text readable?" check.
     */
    fun contrastRatioForAlpha(
        fr: Int, fg: Int, fb: Int,
        alpha: Double,
        br: Int, bg: Int, bb: Int,
    ): Double {
        val (cr, cg, cb) = compositeAlphaOver(fr, fg, fb, alpha, br, bg, bb).let {
            Triple(it[0], it[1], it[2])
        }
        return contrastRatio(
            relativeLuminance(cr, cg, cb),
            relativeLuminance(br, bg, bb),
        )
    }
}
