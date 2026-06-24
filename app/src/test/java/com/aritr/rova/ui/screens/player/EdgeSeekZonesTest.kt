package com.aritr.rova.ui.screens.player

import com.aritr.rova.ui.screens.player.EdgeSeekZones.Zone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PR-7 — pins the double-tap edge-seek zone split. Pure, Compose-free.
 * Bands: [0, leftEdge) = SEEK_BACK, [leftEdge, rightEdge) = TOGGLE,
 * [rightEdge, width] = SEEK_FORWARD. Degenerate width / negative x -> TOGGLE.
 */
class EdgeSeekZonesTest {

    @Test fun `left third seeks back`() {
        assertEquals(Zone.SEEK_BACK, EdgeSeekZones.zoneFor(10f, 300f))
    }

    @Test fun `center third toggles`() {
        assertEquals(Zone.TOGGLE, EdgeSeekZones.zoneFor(150f, 300f))
    }

    @Test fun `right third seeks forward`() {
        assertEquals(Zone.SEEK_FORWARD, EdgeSeekZones.zoneFor(290f, 300f))
    }

    @Test fun `left boundary is exclusive (left band open at leftEdge)`() {
        // x == leftEdge (100) -> center, not back
        assertEquals(Zone.TOGGLE, EdgeSeekZones.zoneFor(100f, 300f))
    }

    @Test fun `right boundary is inclusive (forward band closed at rightEdge)`() {
        // x == rightEdge (200) -> forward
        assertEquals(Zone.SEEK_FORWARD, EdgeSeekZones.zoneFor(200f, 300f))
    }

    @Test fun `degenerate width returns toggle (no divide-by-zero)`() {
        assertEquals(Zone.TOGGLE, EdgeSeekZones.zoneFor(0f, 0f))
        assertEquals(Zone.TOGGLE, EdgeSeekZones.zoneFor(50f, -10f))
    }

    @Test fun `negative tap x returns toggle (safe default)`() {
        assertEquals(Zone.TOGGLE, EdgeSeekZones.zoneFor(-10f, 300f))
    }

    @Test fun `custom fractions honored`() {
        // leftFraction 0.25 -> leftEdge 75; 60 < 75 = back
        assertEquals(Zone.SEEK_BACK, EdgeSeekZones.zoneFor(60f, 300f, 0.25f, 0.25f))
        // rightFraction 0.25 -> rightEdge 225; 80 in [75,225) = center
        assertEquals(Zone.TOGGLE, EdgeSeekZones.zoneFor(80f, 300f, 0.25f, 0.25f))
    }
}
