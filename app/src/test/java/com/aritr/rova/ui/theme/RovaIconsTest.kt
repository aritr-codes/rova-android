package com.aritr.rova.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RovaIconsTest {

    @Test fun warning_status_concept_carries_a_locked_status_role() {
        assertEquals(IconStatus.Warning, RovaIcons.WarningStatus.status)
    }

    @Test fun notifications_setting_is_not_a_status() {
        assertEquals(null, RovaIcons.NotificationsSetting.status)
    }

    @Test fun library_and_play_are_distinct_glyphs() {
        assertNotEquals(RovaIcons.Library.glyph, RovaIcons.Play.glyph)
    }

    @Test fun settings_and_view_are_distinct_glyphs() {
        assertNotEquals(RovaIcons.Settings.glyph, RovaIcons.View.glyph)
    }

    @Test fun sort_resolves_to_a_state_free_material_glyph() {
        assertEquals(Icons.AutoMirrored.Filled.Sort, RovaIcons.Sort.glyph)
    }
}
