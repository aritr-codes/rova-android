package com.aritr.rova.service

import com.aritr.rova.ui.signals.ThermalStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 Slice 3 — pure-JVM tests for [SegmentGateThermal]. Pins the
 * "CRITICAL or above" threshold per ADR-0016. The helper is the only
 * piece of the Layer-4 gate; the service-side call site is dispatch-only.
 */
class SegmentGateThermalTest {

    @Test fun `shouldTerminate true at CRITICAL`() {
        assertTrue(SegmentGateThermal.shouldTerminate(ThermalStatus.CRITICAL))
    }

    @Test fun `shouldTerminate true at EMERGENCY and SHUTDOWN`() {
        assertTrue(SegmentGateThermal.shouldTerminate(ThermalStatus.EMERGENCY))
        assertTrue(SegmentGateThermal.shouldTerminate(ThermalStatus.SHUTDOWN))
    }

    @Test fun `shouldTerminate false below CRITICAL`() {
        assertFalse(SegmentGateThermal.shouldTerminate(ThermalStatus.NONE))
        assertFalse(SegmentGateThermal.shouldTerminate(ThermalStatus.LIGHT))
        assertFalse(SegmentGateThermal.shouldTerminate(ThermalStatus.MODERATE))
        assertFalse(SegmentGateThermal.shouldTerminate(ThermalStatus.SEVERE))
    }
}
