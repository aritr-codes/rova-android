package com.aritr.rova.ui.library

import com.aritr.rova.ui.theme.ThemeSelection
import com.aritr.rova.ui.theme.rovaPalettes
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §3.3 contrast clause: the latest-row tinted container + eyebrow must pass AA across all
 * 12 palettes. The eyebrow is labelSmall (small text) → 4.5:1 floor, computed over the WORST-case
 * opaque background (tint composited on the palette's base surface). Same per-palette iteration
 * pattern as RecordAccentContrastTest.
 */
class LibraryLatestColorsTest {

    private val concrete = ThemeSelection.entries.filter { it != ThemeSelection.FOLLOW_SYSTEM }

    @Test
    fun eyebrow_meetsAA_overTintedContainer_everyPalette() {
        concrete.forEach { sel ->
            val p = rovaPalettes.getValue(sel)
            val ratio = LibraryColorSpec.contrastOver(
                LibraryColorSpec.latestEyebrow(p),
                LibraryColorSpec.latestContainerOver(p),
            )
            assertTrue("$sel: latest eyebrow must be >= 4.5:1 (was ${"%.2f".format(ratio)}:1)", ratio >= 4.5)
        }
    }

    @Test
    fun container_isTranslucentIdentityTint_everyPalette() {
        concrete.forEach { sel ->
            val p = rovaPalettes.getValue(sel)
            val c = LibraryColorSpec.latestContainer(p)
            assertTrue("$sel: container must be a restrained tint (alpha <= 0.2)", c.alpha <= 0.2f)
            assertTrue(
                "$sel: container hue must come from the palette accent",
                c.red == p.accent.red && c.green == p.accent.green && c.blue == p.accent.blue,
            )
        }
    }
}
