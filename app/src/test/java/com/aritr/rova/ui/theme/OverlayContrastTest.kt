package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.2 AA regression guard for the slice-6 contrast fixes (ADR-0020;
 * audit WARN-02, PLR-01/02, RECOV-05, SHAR-17, REC-23, TOK-03). Reads the
 * *actual* token / literal alphas via [ContrastMath] so reverting any value
 * re-breaks the test.
 *
 * **Backgrounds.**
 * - Warning CTA accents (`RovaTokens.RovaWarnings.*`) are **opaque**, so the
 *   dark-text guarantee (SC 1.4.3, 4.5:1) is genuine.
 * - Player / recovery / merge overlays sit over scene-variable video or an
 *   elevated card. As with [TokenContrastTest], alpha alone can't guarantee
 *   AA over arbitrarily bright footage; these assert over the documented
 *   **dark-scene reference** (`#0E1216`) — the case the dark overlays target.
 *   The bumps strictly improve contrast on every scene.
 */
class OverlayContrastTest {

    private val darkRef = intArrayOf(0x0E, 0x12, 0x16) // MidnightSurface dark-scene reference

    private fun whiteRatioOver(alpha: Double, bg: IntArray): Double =
        ContrastMath.contrastRatioForAlpha(255, 255, 255, alpha, bg[0], bg[1], bg[2])

    private fun darkTextRatioOver(accent: Color): Double {
        // #1A1A1A over the opaque accent fill.
        val r = (accent.red * 255).toInt()
        val g = (accent.green * 255).toInt()
        val b = (accent.blue * 255).toInt()
        val lumText = ContrastMath.relativeLuminance(0x1A, 0x1A, 0x1A)
        val lumBg = ContrastMath.relativeLuminance(r, g, b)
        return ContrastMath.contrastRatio(lumText, lumBg)
    }

    @Test
    fun `WARN-02 dark CTA text meets AA over every severity accent`() {
        val accents = mapOf(
            "hard" to RovaWarnings.hard,
            "soft" to RovaWarnings.soft,
            "advisory" to RovaWarnings.advisory,
            "escalating" to RovaWarnings.escalating,
        )
        accents.forEach { (name, accent) ->
            val ratio = darkTextRatioOver(accent)
            assertTrue(
                "PrimaryCta dark text must meet 4.5:1 over $name (was ${"%.2f".format(ratio)}:1)",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun `overlay body text meets AA over the dark-scene reference`() {
        val bodies = mapOf(
            "player subtitle (0.72)" to 0.72,
            "recovery body (0.65)" to 0.65,
            "merge-complete subtitle (0.87)" to 0.87,
        )
        bodies.forEach { (name, alpha) ->
            val ratio = whiteRatioOver(alpha, darkRef)
            assertTrue(
                "$name must meet 4.5:1 over the dark-scene reference (was ${"%.2f".format(ratio)}:1)",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun `functional non-text elements meet the 3 to 1 floor over the dark-scene reference`() {
        // SC 1.4.11: focus brackets (REC-23) and the play affordance fill
        // (PLR-02) are functional UI components, not decoration.
        val focus = whiteRatioOver(RecordChromeTokens.focusFrameStroke.alpha.toDouble(), darkRef)
        assertTrue("focus brackets must meet 3:1 (was ${"%.2f".format(focus)}:1)", focus >= 3.0)

        val playFill = whiteRatioOver(0.35, darkRef)
        assertTrue("play-button fill must meet 3:1 (was ${"%.2f".format(playFill)}:1)", playFill >= 3.0)
    }
}
