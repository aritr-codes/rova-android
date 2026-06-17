package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogActionColorsTest {

    private fun lum(c: IntArray) = ContrastMath.relativeLuminance(c[0], c[1], c[2])

    /** Min contrast of the resolved label over the resolved gradient's worst sample. */
    private fun worstContrast(cta: DialogActionColors.Cta): Double {
        val fg = if (cta.contentWhite) 1.0 else 0.0
        val mid = intArrayOf(
            (cta.start[0] + cta.end[0]) / 2,
            (cta.start[1] + cta.end[1]) / 2,
            (cta.start[2] + cta.end[2]) / 2,
        )
        return listOf(cta.start, mid, cta.end).minOf {
            ContrastMath.contrastRatio(fg, lum(it))
        }
    }

    @Test fun royal_violet_accent_uses_white_label_and_clears_AA() {
        // Eclipse accent #8B7CFF → #B56CFF
        val cta = DialogActionColors.resolve(intArrayOf(0x8B, 0x7C, 0xFF), intArrayOf(0xB5, 0x6C, 0xFF))
        assertTrue("royal violet should take a white label", cta.contentWhite)
        assertTrue("white over violet must clear 4.5:1", worstContrast(cta) >= 4.5)
    }

    @Test fun neon_meadow_accent_uses_dark_label_and_keeps_bright_fill() {
        // Meadow accent #9AE65C → #34D3C0 (very light green/teal)
        val cta = DialogActionColors.resolve(intArrayOf(0x9A, 0xE6, 0x5C), intArrayOf(0x34, 0xD3, 0xC0))
        assertTrue("neon green should take a dark label", !cta.contentWhite)
        // Bright fill kept (not deepened) since black clears at t=0.
        assertEquals(0x9A, cta.start[0])
        assertEquals(0xE6, cta.start[1])
        assertTrue("dark over neon green must clear 4.5:1", worstContrast(cta) >= 4.5)
    }

    @Test fun every_palette_accent_pair_clears_AA() {
        // Guard: no theme's CTA can ship below AA.
        for (p in rovaPalettes.values) {
            val s = intArrayOf(
                (p.accent.red * 255).toInt(),
                (p.accent.green * 255).toInt(),
                (p.accent.blue * 255).toInt(),
            )
            val e = intArrayOf(
                (p.accent2.red * 255).toInt(),
                (p.accent2.green * 255).toInt(),
                (p.accent2.blue * 255).toInt(),
            )
            val cta = DialogActionColors.resolve(s, e)
            assertTrue("${p.id} CTA must clear 4.5:1 (got ${worstContrast(cta)})", worstContrast(cta) >= 4.5)
        }
    }
}
