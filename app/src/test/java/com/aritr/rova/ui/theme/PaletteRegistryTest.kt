package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteRegistryTest {

    @Test
    fun `every concrete selection has a palette`() {
        ThemeSelection.entries
            .filter { it != ThemeSelection.FOLLOW_SYSTEM }
            .forEach { sel -> assertNotNull("missing palette for $sel", rovaPalettes[sel]) }
        assertEquals(12, rovaPalettes.size)
    }

    @Test
    fun `resolvePalette maps follow-system by the OS dark flag`() {
        assertEquals(rovaPalettes.getValue(ThemeSelection.AURORA), resolvePalette(ThemeSelection.FOLLOW_SYSTEM, systemDark = true))
        assertEquals(rovaPalettes.getValue(ThemeSelection.DAYLIGHT), resolvePalette(ThemeSelection.FOLLOW_SYSTEM, systemDark = false))
        assertEquals(rovaPalettes.getValue(ThemeSelection.TIDE), resolvePalette(ThemeSelection.TIDE, systemDark = true))
    }

    @Test
    fun `semantics are locked and identical for all palettes`() {
        assertEquals(Color(0xFF34D399), RovaSemantics.success)
        assertEquals(Color(0xFFFBBF24), RovaSemantics.warning)
        assertEquals(Color(0xFFEF4444), RovaSemantics.error)
        assertEquals(Color(0xFFF97316), RovaSemantics.escalating)
        assertEquals(Color(0xFFFF4D4D), RovaSemantics.rec)
    }

    @Test
    fun `only daylight is light`() {
        rovaPalettes.forEach { (sel, p) ->
            assertEquals("isLight wrong for $sel", sel == ThemeSelection.DAYLIGHT, p.isLight)
        }
    }

    @Test
    fun `aurora is the default palette id`() {
        assertEquals(ThemeSelection.AURORA, rovaPalettes.getValue(ThemeSelection.AURORA).id)
        assertTrue(rovaPalettes.containsKey(ThemeSelection.DEFAULT))
    }

    @Test
    fun `every palette has an opaque surfaceBase matching its darkest background stop`() {
        val expected = mapOf(
            ThemeSelection.AURORA to Color(0xFF141622),
            ThemeSelection.TIDE to Color(0xFF0E1A1F),
            ThemeSelection.JADE to Color(0xFF0C1C18),
            ThemeSelection.DUSK to Color(0xFF1F1310),
            ThemeSelection.ECLIPSE to Color(0xFF000000),
            ThemeSelection.DAYLIGHT to Color(0xFFF4F1EA),
            ThemeSelection.BLOSSOM to Color(0xFF1E1220),
            ThemeSelection.CORAL to Color(0xFF1F1510),
            ThemeSelection.MEADOW to Color(0xFF12190E),
            ThemeSelection.COBALT to Color(0xFF0E1230),
            ThemeSelection.ORCHID to Color(0xFF1E1019),
            ThemeSelection.GRAPHITE to Color(0xFF0E0F12),
        )
        expected.forEach { (sel, base) ->
            val p = rovaPalettes.getValue(sel)
            assertEquals("$sel surfaceBase", base, p.surfaceBase)
            assertEquals("$sel surfaceBase must be opaque", 1f, p.surfaceBase.alpha)
        }
    }
}
