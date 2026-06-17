package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * Regression guard for the 2026-06-17 init-order bug: a glyph `val` that referenced the
     * later-declared `PLACEHOLDER` brush built every path with `stroke = null` / `fill = null` —
     * valid geometry but NO brush, so `Icon(tint = …)` painted nothing (invisible on device).
     * Every authored path MUST carry a stroke or fill brush.
     */
    @Test fun every_glyph_path_has_a_brush() {
        listOf(
            "Library.outline" to RovaGlyphs.Library.outline,
            "Library.accent" to RovaGlyphs.Library.accent!!,
            "Settings.outline" to RovaGlyphs.Settings.outline,
            "Settings.accent" to RovaGlyphs.Settings.accent!!,
            "Sort.outline" to RovaGlyphs.Sort.outline,
            "Sort.accent" to RovaGlyphs.Sort.accent!!,
            "Record.outline" to RovaGlyphs.Record.outline,
        ).forEach { (name, iv) -> assertEveryPathHasBrush(name, iv.root) }
    }

    private fun assertEveryPathHasBrush(name: String, node: VectorNode) {
        when (node) {
            is VectorPath -> assertTrue(
                "$name has a path with NO brush (stroke=${node.stroke}, fill=${node.fill})",
                node.stroke != null || node.fill != null,
            )
            is VectorGroup -> node.forEach { assertEveryPathHasBrush(name, it) }
            else -> {}
        }
    }
}
