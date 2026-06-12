package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureModesTest {

    @Test
    fun visible_isAutoAndDualShot_whileDualSightDisabled() {
        // PR-γ ships DualSight as a registry entry only (spec §3); flips in PR-δ.
        assertEquals(listOf(CaptureMode.Auto, CaptureMode.DualShot), CaptureMode.visible())
    }

    @Test
    fun forTopology_mapsPersistedStrings_andDefaultsToAuto() {
        assertEquals(CaptureMode.Auto, CaptureMode.forTopology("Single"))
        assertEquals(CaptureMode.DualShot, CaptureMode.forTopology("DualShot"))
        assertEquals(CaptureMode.DualSight, CaptureMode.forTopology("FrontBack"))
        assertEquals(CaptureMode.Auto, CaptureMode.forTopology("Portrait")) // legacy/garbage
    }

    @Test
    fun isAccented_onlyForNonDefaultModes() {
        // Spec §5 Variant A (owner-ratified): accent iff mode != Auto.
        assertEquals(false, CaptureMode.isAccented("Single"))
        assertEquals(true, CaptureMode.isAccented("DualShot"))
        assertEquals(true, CaptureMode.isAccented("FrontBack"))
    }

    @Test
    fun cycleNext_loopsThroughVisibleModesOnly() {
        assertEquals("DualShot", CaptureMode.cycleNext("Single"))
        assertEquals("Single", CaptureMode.cycleNext("DualShot"))
        assertEquals("Single", CaptureMode.cycleNext("FrontBack")) // hidden -> snaps into visible ring
        assertEquals("DualShot", CaptureMode.cycleNext("garbage"))  // forTopology -> Auto -> next
    }

    @Test
    fun lockRotationForLandscapePick_usesCurrentRotationWhenLandscape_else90() {
        assertEquals(1, lockRotationForLandscapePick(1))
        assertEquals(3, lockRotationForLandscapePick(3))
        assertEquals(1, lockRotationForLandscapePick(0))
        assertEquals(1, lockRotationForLandscapePick(2))
        assertEquals(1, lockRotationForLandscapePick(null))
    }
}
