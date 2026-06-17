package com.aritr.rova.ui.screens

import com.aritr.rova.ui.theme.RovaIcons
import org.junit.Assert.assertEquals
import org.junit.Test

class PickerGlyphsTest {

    @Test fun capture_modes_map_to_their_glyphs() {
        assertEquals(RovaIcons.Single, captureGlyphFor(CaptureMode.Auto))
        assertEquals(RovaIcons.DualShot, captureGlyphFor(CaptureMode.DualShot))
        assertEquals(RovaIcons.DualSight, captureGlyphFor(CaptureMode.DualSight))
    }

    @Test fun orientation_options_map_to_their_glyphs() {
        assertEquals(RovaIcons.FollowDevice, orientationGlyphFor("FollowDevice", -1))
        assertEquals(RovaIcons.OrientationPortrait, orientationGlyphFor("Lock", 0))
        assertEquals(RovaIcons.OrientationLandscape, orientationGlyphFor("Lock", 1))
        assertEquals(RovaIcons.OrientationLandscape, orientationGlyphFor("Lock", 3))
    }
}
