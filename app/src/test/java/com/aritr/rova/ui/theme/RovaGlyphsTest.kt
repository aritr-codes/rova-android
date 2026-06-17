package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test fun dualshot_is_duotone_twin_frames() {
        assertNotNull(RovaGlyphs.DualShot.outline)
        assertNotNull("DualShot accent = solid core dot", RovaGlyphs.DualShot.accent)
    }

    @Test fun vault_is_duotone_stack_with_lock() {
        assertNotNull(RovaGlyphs.Vault.outline)
        assertNotNull("Vault accent = back frame + padlock", RovaGlyphs.Vault.accent)
    }

    @Test fun recovery_is_duotone_rejoined_frame() {
        assertNotNull(RovaGlyphs.Recovery.outline)
        assertNotNull("Recovery accent = seam", RovaGlyphs.Recovery.accent)
    }

    @Test fun dualsight_is_duotone_frame_with_pip() {
        assertNotNull(RovaGlyphs.DualSight.outline)
        assertNotNull("DualSight accent = PiP inset", RovaGlyphs.DualSight.accent)
    }

    @Test fun background_record_is_duotone_card_with_dot() {
        assertNotNull(RovaGlyphs.BackgroundRecord.outline)
        assertNotNull("BackgroundRecord accent = rec dot", RovaGlyphs.BackgroundRecord.accent)
    }

    @Test fun merge_is_duotone_streams_to_node() {
        assertNotNull(RovaGlyphs.Merge.outline)
        assertNotNull("Merge accent = join node", RovaGlyphs.Merge.accent)
    }

    @Test fun orientation_portrait_is_duotone_phone() {
        assertNotNull(RovaGlyphs.OrientationPortrait.outline)
        assertNotNull("Portrait accent = speaker bar", RovaGlyphs.OrientationPortrait.accent)
    }

    @Test fun orientation_landscape_is_duotone_phone() {
        assertNotNull(RovaGlyphs.OrientationLandscape.outline)
        assertNotNull("Landscape accent = speaker bar", RovaGlyphs.OrientationLandscape.accent)
    }

    @Test fun orientation_glyphs_differ() {
        // Portrait taller-than-wide, landscape wider-than-tall: their outline path
        // data must not be identical (guards against a copy-paste author bug).
        assertNotEquals(
            RovaGlyphs.OrientationPortrait.outline.root.toString(),
            RovaGlyphs.OrientationLandscape.outline.root.toString(),
        )
    }

    @Test fun all_glyphs_use_the_24_grid() {
        listOf(
            RovaGlyphs.Library, RovaGlyphs.Settings, RovaGlyphs.Sort, RovaGlyphs.Record,
            RovaGlyphs.DualShot, RovaGlyphs.Vault, RovaGlyphs.Recovery, RovaGlyphs.DualSight,
            RovaGlyphs.BackgroundRecord, RovaGlyphs.Merge,
            RovaGlyphs.OrientationPortrait, RovaGlyphs.OrientationLandscape,
        ).forEach { g ->
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
            "DualShot.outline" to RovaGlyphs.DualShot.outline,
            "DualShot.accent" to RovaGlyphs.DualShot.accent!!,
            "Vault.outline" to RovaGlyphs.Vault.outline,
            "Vault.accent" to RovaGlyphs.Vault.accent!!,
            "Recovery.outline" to RovaGlyphs.Recovery.outline,
            "Recovery.accent" to RovaGlyphs.Recovery.accent!!,
            "DualSight.outline" to RovaGlyphs.DualSight.outline,
            "DualSight.accent" to RovaGlyphs.DualSight.accent!!,
            "BackgroundRecord.outline" to RovaGlyphs.BackgroundRecord.outline,
            "BackgroundRecord.accent" to RovaGlyphs.BackgroundRecord.accent!!,
            "Merge.outline" to RovaGlyphs.Merge.outline,
            "Merge.accent" to RovaGlyphs.Merge.accent!!,
            "OrientationPortrait.outline" to RovaGlyphs.OrientationPortrait.outline,
            "OrientationPortrait.accent" to RovaGlyphs.OrientationPortrait.accent!!,
            "OrientationLandscape.outline" to RovaGlyphs.OrientationLandscape.outline,
            "OrientationLandscape.accent" to RovaGlyphs.OrientationLandscape.accent!!,
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
