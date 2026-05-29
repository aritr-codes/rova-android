package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ContrastMath] — the WCAG 2.1 relative-luminance /
 * contrast-ratio math seam introduced for the WCAG 2.2 AA remediation
 * (ADR-0020; audit `docs/accessibility/2026-05-29-wcag-2.2-aa-audit.md`).
 *
 * The "effective background" constants below are the M3 *tonal-elevated*
 * surfaces the 2026-05-29 audit measured against — NOT the raw surface
 * tokens. A ModalBottomSheet / elevated card paints `surfaceContainer*`
 * with a tonal overlay, which is lighter than the base `MidnightSurface`
 * (#0E1216) and therefore *lowers* contrast for white text. Testing against
 * the raw token would falsely pass the failing values; these grays
 * reproduce the audit's measured ratios.
 */
class ContrastMathTest {

    /** Effective WarningSheetV3 body background (M3 elevated sheet ≈ #2C2C2C). */
    private val sheetBg = intArrayOf(0x2C, 0x2C, 0x2C)

    /** Effective RecoveryCard header background (M3 elevated card ≈ gray 54). */
    private val recoveryBg = intArrayOf(54, 54, 54)

    @Test
    fun `black on white is the canonical 21 to 1`() {
        val lWhite = ContrastMath.relativeLuminance(255, 255, 255)
        val lBlack = ContrastMath.relativeLuminance(0, 0, 0)
        assertEquals(21.0, ContrastMath.contrastRatio(lWhite, lBlack), 0.01)
    }

    @Test
    fun `identical colors are 1 to 1`() {
        val l = ContrastMath.relativeLuminance(44, 44, 44)
        assertEquals(1.0, ContrastMath.contrastRatio(l, l), 0.0001)
    }

    @Test
    fun `ratio is symmetric in argument order`() {
        val a = ContrastMath.relativeLuminance(255, 255, 255)
        val b = ContrastMath.relativeLuminance(0x2C, 0x2C, 0x2C)
        assertEquals(
            ContrastMath.contrastRatio(a, b),
            ContrastMath.contrastRatio(b, a),
            0.0,
        )
    }

    @Test
    fun `compositing fully opaque white over black yields white`() {
        val out = ContrastMath.compositeAlphaOver(255, 255, 255, 1.0, 0, 0, 0)
        assertEquals(255, out[0])
        assertEquals(255, out[1])
        assertEquals(255, out[2])
    }

    @Test
    fun `compositing fully transparent fg yields the background`() {
        val out = ContrastMath.compositeAlphaOver(255, 255, 255, 0.0, 14, 18, 22)
        assertEquals(14, out[0])
        assertEquals(18, out[1])
        assertEquals(22, out[2])
    }

    @Test
    fun `raising white alpha over a dark bg strictly increases contrast`() {
        val low = ContrastMath.contrastRatioForAlpha(255, 255, 255, 0.30, 0x2C, 0x2C, 0x2C)
        val high = ContrastMath.contrastRatioForAlpha(255, 255, 255, 0.60, 0x2C, 0x2C, 0x2C)
        assertTrue("higher alpha must give higher contrast on dark bg", high > low)
    }

    // --- Regression: WARN-01 (Blocker) — WarningSheetV3 body text ---

    @Test
    fun `WARN-01 old 0_45 alpha body text fails AA on the elevated sheet`() {
        val ratio = ContrastMath.contrastRatioForAlpha(
            255, 255, 255, 0.45, sheetBg[0], sheetBg[1], sheetBg[2],
        )
        assertTrue("old 0.45α must be below the 4.5:1 AA bar (was ~4.10:1)", ratio < 4.5)
    }

    @Test
    fun `WARN-01 fixed 0_55 alpha body text meets AA on the elevated sheet`() {
        val ratio = ContrastMath.contrastRatioForAlpha(
            255, 255, 255, 0.55, sheetBg[0], sheetBg[1], sheetBg[2],
        )
        assertTrue("fixed 0.55α must meet the 4.5:1 AA bar (≈5.34:1)", ratio >= 4.5)
    }

    // --- Regression: RECOV-06 (Blocker) — RecoveryCard progress header ---

    @Test
    fun `RECOV-06 old 0_36 alpha header fails AA on the elevated card`() {
        val ratio = ContrastMath.contrastRatioForAlpha(
            255, 255, 255, 0.36, recoveryBg[0], recoveryBg[1], recoveryBg[2],
        )
        assertTrue("old 0.36α must be below the 4.5:1 AA bar (was ~3:1)", ratio < 4.5)
    }

    @Test
    fun `RECOV-06 fixed 0_70 alpha header meets AA on the elevated card`() {
        val ratio = ContrastMath.contrastRatioForAlpha(
            255, 255, 255, 0.70, recoveryBg[0], recoveryBg[1], recoveryBg[2],
        )
        assertTrue("fixed 0.70α must meet the 4.5:1 AA bar (≈6.83:1)", ratio >= 4.5)
    }
}
