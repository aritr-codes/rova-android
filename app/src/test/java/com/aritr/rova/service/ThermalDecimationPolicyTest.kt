package com.aritr.rova.service

import com.aritr.rova.ui.signals.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalDecimationPolicyTest {
    @Test fun factor_isOne_belowSevere() {
        for (s in listOf(ThermalStatus.NONE, ThermalStatus.LIGHT, ThermalStatus.MODERATE)) {
            assertEquals("$s should not decimate", 1, ThermalDecimationPolicy.decimationFactor(s))
        }
    }

    @Test fun factor_isTwo_atSevereAndAbove() {
        for (s in listOf(ThermalStatus.SEVERE, ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN)) {
            assertEquals("$s should decimate", 2, ThermalDecimationPolicy.decimationFactor(s))
        }
    }

    @Test fun shouldSubmit_factorOne_passesEveryFrame() {
        for (c in 0..5) assertTrue(ThermalDecimationPolicy.shouldSubmit(c, 1))
    }

    @Test fun shouldSubmit_factorTwo_passesEveryOtherFrame() {
        assertTrue(ThermalDecimationPolicy.shouldSubmit(0, 2))
        assertFalse(ThermalDecimationPolicy.shouldSubmit(1, 2))
        assertTrue(ThermalDecimationPolicy.shouldSubmit(2, 2))
        assertFalse(ThermalDecimationPolicy.shouldSubmit(3, 2))
    }
}
