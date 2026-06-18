package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteColorSchemeTest {

    private val concrete = ThemeSelection.entries.filter { it != ThemeSelection.FOLLOW_SYSTEM }

    @Test fun `from is deterministic`() {
        concrete.forEach { sel ->
            val p = rovaPalettes.getValue(sel)
            val a = PaletteColorScheme.from(p)
            val b = PaletteColorScheme.from(p)
            assertEquals("$sel primary", a.primary, b.primary)
            assertEquals("$sel surface", a.surface, b.surface)
            assertEquals("$sel error", a.error, b.error)
        }
    }

    @Test fun `error and scrim are locked, never palette-derived`() {
        concrete.forEach { sel ->
            val s = PaletteColorScheme.from(rovaPalettes.getValue(sel))
            assertEquals("$sel error", RovaSemantics.error, s.error)
            assertEquals("$sel scrim", Color.Black, s.scrim)
        }
    }

    @Test fun `surface and container slots are fully opaque`() {
        concrete.forEach { sel ->
            val s = PaletteColorScheme.from(rovaPalettes.getValue(sel))
            listOf(
                "background" to s.background, "surface" to s.surface,
                "surfaceVariant" to s.surfaceVariant, "surfaceDim" to s.surfaceDim,
                "surfaceBright" to s.surfaceBright,
                "surfaceContainerLowest" to s.surfaceContainerLowest,
                "surfaceContainerLow" to s.surfaceContainerLow,
                "surfaceContainer" to s.surfaceContainer,
                "surfaceContainerHigh" to s.surfaceContainerHigh,
                "surfaceContainerHighest" to s.surfaceContainerHighest,
                "outline" to s.outline, "outlineVariant" to s.outlineVariant,
                "primaryContainer" to s.primaryContainer, "errorContainer" to s.errorContainer,
            ).forEach { (name, c) -> assertTrue("$sel $name opaque", c.alpha == 1f) }
        }
    }

    @Test fun `surface container ladder is monotonic in luminance off surfaceBase`() {
        // Dark palettes raise toward white (increasing luminance); Daylight lowers.
        concrete.forEach { sel ->
            val s = PaletteColorScheme.from(rovaPalettes.getValue(sel))
            val rungs = listOf(
                s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer,
                s.surfaceContainerHigh, s.surfaceContainerHighest,
            ).map { lum(it) }
            val ascending = rungs.zipWithNext().all { (a, b) -> b >= a - 1e-6 }
            val descending = rungs.zipWithNext().all { (a, b) -> b <= a + 1e-6 }
            assertTrue("$sel ladder must be monotonic", ascending || descending)
        }
    }

    @Test fun `onPrimary clears AA over the assigned primary fill`() {
        concrete.forEach { sel ->
            val s = PaletteColorScheme.from(rovaPalettes.getValue(sel))
            assertTrue("$sel onPrimary/primary ${ratio(s.onPrimary, s.primary)}",
                ratio(s.onPrimary, s.primary) >= 4.5)
            assertTrue("$sel onSecondary/secondary", ratio(s.onSecondary, s.secondary) >= 4.5)
        }
    }

    private fun lum(c: Color) =
        ContrastMath.relativeLuminance((c.red*255).toInt(), (c.green*255).toInt(), (c.blue*255).toInt())
    private fun ratio(fg: Color, bg: Color) = ContrastMath.contrastRatio(lum(fg), lum(bg))
}
