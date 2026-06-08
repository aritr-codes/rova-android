package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PinnedGlassEnvironmentTest {

    private fun envFor(selection: ThemeSelection): GlassEnvironment =
        GlassEnvironment(
            palette = rovaPalettes.getValue(selection),
            apiLevel = 34,
            reduceTransparency = false,
        )

    @Test
    fun `pinned env uses neutral-dark surface, not the active palette surface`() {
        val pinned = PinnedGlassEnvironment.forPinnedRoute(envFor(ThemeSelection.DAYLIGHT))
        assertEquals(NeutralDarkRecordPalette.glassTint, pinned.palette.glassTint)
        assertEquals(NeutralDarkRecordPalette.edge, pinned.palette.edge)
        assertFalse(pinned.palette.isLight)
    }

    @Test
    fun `pinned env carries the active palette dark-safe accents`() {
        val active = envFor(ThemeSelection.JADE)
        val pinned = PinnedGlassEnvironment.forPinnedRoute(active)
        assertEquals(active.palette.accentOnDark, pinned.palette.accentOnDark)
        assertEquals(active.palette.accentContainerOnDark, pinned.palette.accentContainerOnDark)
    }

    @Test
    fun `pinned env preserves apiLevel and reduceTransparency`() {
        val active = GlassEnvironment(rovaPalettes.getValue(ThemeSelection.TIDE), apiLevel = 26, reduceTransparency = true)
        val pinned = PinnedGlassEnvironment.forPinnedRoute(active)
        assertEquals(26, pinned.apiLevel)
        assertEquals(true, pinned.reduceTransparency)
    }
}
