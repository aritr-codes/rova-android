package com.aritr.rova.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePolicyTest {
    @Test fun threshold_scalesAndClamps() {
        assertEquals(1000L, ResumePolicy.nearEndThresholdMs(10_000L))
        assertEquals(3000L, ResumePolicy.nearEndThresholdMs(10_000_000L))
        assertEquals(2000L, ResumePolicy.nearEndThresholdMs(100_000L))
    }

    @Test fun restart_whenWithinThresholdOfEnd() {
        assertTrue(ResumePolicy.shouldRestartFromStart(99_500L, 100_000L))
        assertFalse(ResumePolicy.shouldRestartFromStart(50_000L, 100_000L))
        assertFalse(ResumePolicy.shouldRestartFromStart(0L, 0L))
    }

    @Test fun resolveOpen_clampsAndRestarts() {
        assertEquals(0L, ResumePolicy.resolveOpenPosition(null, 100_000L))
        assertEquals(50_000L, ResumePolicy.resolveOpenPosition(50_000L, 100_000L))
        assertEquals(100_000L, ResumePolicy.resolveOpenPosition(999_999L, 100_000L))
        assertEquals(0L, ResumePolicy.resolveOpenPosition(99_900L, 100_000L))
    }
}
