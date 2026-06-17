package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RovaIconsTest {

    @Test fun warning_status_concept_carries_a_locked_status_role() {
        assertEquals(IconStatus.Warning, RovaIcons.WarningStatus.status)
    }

    @Test fun notifications_setting_is_not_a_status() {
        assertNull(RovaIcons.NotificationsSetting.status)
    }

    @Test fun library_and_play_are_distinct_glyphs() {
        assertNotEquals(RovaIcons.Library.outline, RovaIcons.Play.glyph)
    }

    @Test fun settings_and_view_are_distinct_glyphs() {
        assertNotEquals(RovaIcons.Settings.outline, RovaIcons.View.glyph)
    }

    @Test fun bespoke_concepts_resolve_to_RovaGlyphs() {
        assertEquals(RovaGlyphs.Library, RovaIcons.Library)
        assertEquals(RovaGlyphs.Settings, RovaIcons.Settings)
        assertEquals(RovaGlyphs.Sort, RovaIcons.Sort)
        assertEquals(RovaGlyphs.Record, RovaIcons.Record)
    }

    @Test fun brand_and_orientation_concepts_resolve_to_bespoke_glyphs() {
        assertEquals(RovaGlyphs.DualShot, RovaIcons.DualShot)
        assertEquals(RovaGlyphs.Vault, RovaIcons.Vault)
        assertEquals(RovaGlyphs.Recovery, RovaIcons.Recovery)
        assertEquals(RovaGlyphs.DualSight, RovaIcons.DualSight)
        assertEquals(RovaGlyphs.BackgroundRecord, RovaIcons.BackgroundRecord)
        assertEquals(RovaGlyphs.Merge, RovaIcons.Merge)
        assertEquals(RovaGlyphs.OrientationPortrait, RovaIcons.OrientationPortrait)
        assertEquals(RovaGlyphs.OrientationLandscape, RovaIcons.OrientationLandscape)
    }
}
