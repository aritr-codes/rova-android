package com.aritr.rova.ui.theme

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

class SettingsGlyphWiringTest {

    // `outline` is a non-null type, so its presence is compile-guaranteed; the meaningful authoring
    // assertion is that each glyph carries the duotone *accent* layer (nullable) round-tripped from
    // the board's `ac2` paths.
    @Test fun loop_interval_glyph_has_a_duotone_accent_layer() {
        assertNotNull("LoopInterval has a duotone accent layer", RovaGlyphs.LoopInterval.accent)
    }

    @Test fun volume_glyph_has_a_duotone_accent_layer() {
        assertNotNull("Volume has a duotone accent layer", RovaGlyphs.Volume.accent)
    }

    @Test fun map_entries_point_at_the_new_glyphs() {
        assertSame(RovaGlyphs.LoopInterval, RovaIcons.LoopInterval)
        assertSame(RovaGlyphs.Volume, RovaIcons.Volume)
    }

    @Test fun interval_alias_reuses_the_existing_waiting_hourglass() {
        assertSame(RovaGlyphs.Waiting, RovaIcons.Interval)
    }
}
