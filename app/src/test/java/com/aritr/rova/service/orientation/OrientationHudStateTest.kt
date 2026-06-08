package com.aritr.rova.service.orientation

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PR-α (ADR-0029 §Decision 3) — pure tests for the current-vs-pending HUD state. */
class OrientationHudStateTest {

    @Test fun `equal current and pending means not rotating`() {
        val s = orientationHud(currentSegmentRotation = Surface.ROTATION_0, pendingNextRotation = Surface.ROTATION_0)
        assertFalse(s.rotatingNextClip)
        assertEquals(Surface.ROTATION_0, s.currentSegmentRotation)
        assertEquals(Surface.ROTATION_0, s.pendingNextRotation)
    }

    @Test fun `differing current and pending means rotating next clip`() {
        val s = orientationHud(currentSegmentRotation = Surface.ROTATION_0, pendingNextRotation = Surface.ROTATION_270)
        assertTrue(s.rotatingNextClip)
        assertEquals(Surface.ROTATION_0, s.currentSegmentRotation)
        assertEquals(Surface.ROTATION_270, s.pendingNextRotation)
    }
}
