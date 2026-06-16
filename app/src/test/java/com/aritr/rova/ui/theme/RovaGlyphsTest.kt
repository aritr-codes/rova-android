package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RovaGlyphsTest {

    @Test fun library_is_duotone_stacked_frames() {
        assertNotNull(RovaGlyphs.Library.outline)
        assertNotNull("Library accent (top frame) drives the duotone channel", RovaGlyphs.Library.accent)
    }

    @Test fun settings_is_duotone_gear() {
        assertNotNull(RovaGlyphs.Settings.outline)
        assertNotNull(RovaGlyphs.Settings.accent)
    }

    @Test fun sort_is_duotone_bars() {
        assertNotNull(RovaGlyphs.Sort.outline)
        assertNotNull(RovaGlyphs.Sort.accent)
    }

    @Test fun record_disc_is_mono() {
        assertNotNull(RovaGlyphs.Record.outline)
        assertNull("Record action disc is a single mono mark", RovaGlyphs.Record.accent)
    }

    @Test fun all_glyphs_use_the_24_grid() {
        listOf(RovaGlyphs.Library, RovaGlyphs.Settings, RovaGlyphs.Sort, RovaGlyphs.Record).forEach { g ->
            assertEquals(24f, g.outline.viewportWidth, 0f)
            assertEquals(24f, g.outline.viewportHeight, 0f)
            g.accent?.let {
                assertEquals(24f, it.viewportWidth, 0f)
                assertEquals(24f, it.viewportHeight, 0f)
            }
        }
    }
}
