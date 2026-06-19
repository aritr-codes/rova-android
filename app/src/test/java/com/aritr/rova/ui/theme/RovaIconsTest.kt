package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RovaIconsTest {

    @Test fun warning_status_concept_carries_a_locked_status_role() {
        assertEquals(IconStatus.Warning, RovaIcons.WarningStatus.status)
    }

    @Test fun notifications_setting_is_a_bespoke_chrome_glyph_not_a_status() {
        // PR-3 flipped NotificationsSetting off stock Material to the bespoke bell glyph. It is a
        // bare RovaGlyph (a role-tinted chrome toggle) — it has no status channel at all.
        assertEquals(RovaGlyphs.NotifBell, RovaIcons.NotificationsSetting)
    }

    @Test fun details_is_a_setting_not_a_status() {
        assertNull(RovaIcons.Details.status)
    }

    @Test fun library_and_play_are_distinct_glyphs() {
        assertNotEquals(RovaIcons.Library.outline, RovaIcons.Play.glyph)
    }

    @Test fun settings_and_view_are_distinct_glyphs() {
        // PR-3 flipped View off Icons.Default.GridView to the bespoke 2×2-grid RovaGlyph.
        assertNotEquals(RovaIcons.Settings.outline, RovaIcons.View.outline)
    }

    @Test fun pr3_flips_resolve_to_bespoke_glyphs() {
        assertEquals(RovaGlyphs.View, RovaIcons.View)
        assertEquals(RovaGlyphs.NotifBell, RovaIcons.NotificationsSetting)
        // WarningStatus stays a RovaIcon so the locked status travels with the concept;
        // WarnTriangle is mono so its single mark is the exposed image vector.
        assertEquals(RovaGlyphs.WarnTriangle.outline, RovaIcons.WarningStatus.glyph)
        assertEquals(IconStatus.Warning, RovaIcons.WarningStatus.status)
    }

    @Test fun pr3_everyday_action_concepts_resolve_to_bespoke_glyphs() {
        assertEquals(RovaGlyphs.Search, RovaIcons.Search)
        assertEquals(RovaGlyphs.Share, RovaIcons.Share)
        assertEquals(RovaGlyphs.Delete, RovaIcons.Delete)
        assertEquals(RovaGlyphs.Favorite, RovaIcons.Favorite)
        assertEquals(RovaGlyphs.FavoriteOn, RovaIcons.FavoriteOn)
        assertEquals(RovaGlyphs.Select, RovaIcons.Select)
        assertEquals(RovaGlyphs.Pause, RovaIcons.Pause)
        assertEquals(RovaGlyphs.Edit, RovaIcons.Edit)
        assertEquals(RovaGlyphs.Theme, RovaIcons.Theme)
        assertEquals(RovaGlyphs.NotifOff, RovaIcons.NotificationsOff)
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
        assertEquals(RovaGlyphs.Single, RovaIcons.Single)
        assertEquals(RovaGlyphs.FollowDevice, RovaIcons.FollowDevice)
    }
}
