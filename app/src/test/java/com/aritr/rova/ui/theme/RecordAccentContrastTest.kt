package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Per-palette legibility of the RESTRAINED record selected-state accent
 * (ADR-0028 §2.4, codex-reconciled 2026-06-08). The selected mode segment is a
 * dark `accentContainerOnDark` (accent@22%) tint, NOT a solid bright fill — a
 * solid accentOnDark fill behind white text fails 3:1 for 10/12 themes. Three
 * bars match the three real relationships; if any palette fails, fix that
 * palette's accent in RovaPalette.kt, never the threshold.
 */
class RecordAccentContrastTest {

    private val recordSurface = Color(0xFF0B0E14)

    private fun rgb(c: Color) = Triple(
        (c.red * 255).roundToInt(),
        (c.green * 255).roundToInt(),
        (c.blue * 255).roundToInt(),
    )

    private fun lum(c: Color): Double { val (r, g, b) = rgb(c); return ContrastMath.relativeLuminance(r, g, b) }
    private fun ratio(a: Color, b: Color): Double = ContrastMath.contrastRatio(lum(a), lum(b))

    private fun selectedFill(accent: Color): IntArray {
        val (ar, ag, ab) = rgb(accent)
        val (br, bg, bb) = rgb(recordSurface)
        return ContrastMath.compositeAlphaOver(ar, ag, ab, 0.22, br, bg, bb)
    }

    private fun ratioToFill(c: Color, fill: IntArray): Double {
        val (r, g, b) = rgb(c)
        return ContrastMath.contrastRatio(
            ContrastMath.relativeLuminance(r, g, b),
            ContrastMath.relativeLuminance(fill[0], fill[1], fill[2]),
        )
    }

    @Test
    fun `selected mode segment accent is legible in every theme`() {
        ThemeSelection.entries
            .filter { it != ThemeSelection.FOLLOW_SYSTEM }
            .forEach { sel ->
                val p = rovaPalettes.getValue(sel)
                val fill = selectedFill(p.accentOnDark)

                val accOnSurface = ratio(p.accentOnDark, recordSurface)
                assertTrue("$sel: accentOnDark-on-surface = $accOnSurface (< 3.0)", accOnSurface >= 3.0)

                val whiteOnFill = ratioToFill(Color.White, fill)
                assertTrue("$sel: white-on-selectedFill = $whiteOnFill (< 4.5)", whiteOnFill >= 4.5)

                val accOnFill = ratioToFill(p.accentOnDark, fill)
                assertTrue("$sel: accentOnDark-on-selectedFill = $accOnFill (< 3.0)", accOnFill >= 3.0)
            }
    }
}
