package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RovaMotionTest {

    @Test
    fun `container spring constants match the spec`() {
        assertEquals(380f, RovaMotion.containerStiffness, 0f)
        assertEquals(30f, RovaMotion.containerDamping, 0f)
    }

    @Test
    fun `standard durations match the spec`() {
        assertEquals(120, RovaMotion.chipToggleMs)
        assertEquals(200, RovaMotion.dockShrinkMs)
    }

    @Test
    fun `record pulse is in the 1800-2200ms band`() {
        assertTrue(RovaMotion.recordPulseMs in 1800..2200)
    }
}
