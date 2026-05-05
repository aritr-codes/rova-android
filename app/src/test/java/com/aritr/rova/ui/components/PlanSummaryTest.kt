package com.aritr.rova.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins the four sentinel combinations of the Record session-plan
 * summary. The presentation boundary maps `loopCount = -1` to
 * "until stopped" and `intervalMinutes = 0` to "no wait" so the user
 * never sees `-1`, `∞`, or `0 m`. Slice 2 will round-trip these
 * sentinels at the VM-write boundary; the formatter is sentinel-blind
 * by design.
 */
class PlanSummaryTest {

    @Test
    fun `Fixed repeats with finite wait`() {
        val summary = formatSessionPlan(
            clipSeconds = 30,
            loopCount = 10,
            intervalMinutes = 1,
            quality = "FHD"
        )
        assertEquals("30s clips · 10 repeats · 1m wait · FHD", summary)
    }

    @Test
    fun `Continuous repeats with finite wait`() {
        val summary = formatSessionPlan(
            clipSeconds = 30,
            loopCount = -1,
            intervalMinutes = 1,
            quality = "FHD"
        )
        assertEquals("30s clips · until stopped · 1m wait · FHD", summary)
    }

    @Test
    fun `Fixed repeats with None wait`() {
        val summary = formatSessionPlan(
            clipSeconds = 30,
            loopCount = 10,
            intervalMinutes = 0,
            quality = "FHD"
        )
        assertEquals("30s clips · 10 repeats · no wait · FHD", summary)
    }

    @Test
    fun `Continuous repeats with None wait`() {
        val summary = formatSessionPlan(
            clipSeconds = 30,
            loopCount = -1,
            intervalMinutes = 0,
            quality = "FHD"
        )
        assertEquals("30s clips · until stopped · no wait · FHD", summary)
    }

    @Test
    fun `clip length renders minutes when an even multiple of 60`() {
        val summary = formatSessionPlan(
            clipSeconds = 60,
            loopCount = 5,
            intervalMinutes = 5,
            quality = "HD"
        )
        assertEquals("1m clips · 5 repeats · 5m wait · HD", summary)
    }

    @Test
    fun `wait renders 1h when intervalMinutes is 60`() {
        val summary = formatSessionPlan(
            clipSeconds = 30,
            loopCount = 10,
            intervalMinutes = 60,
            quality = "4K"
        )
        assertEquals("30s clips · 10 repeats · 1h wait · 4K", summary)
    }

    @Test
    fun `negative loopCount always reads as until stopped, not just -1`() {
        // Defends against a future caller passing any negative sentinel
        // (e.g., -2) — UI must never render the sign or absolute value.
        val summary = formatSessionPlan(
            clipSeconds = 30,
            loopCount = -42,
            intervalMinutes = 1,
            quality = "FHD"
        )
        assertEquals("30s clips · until stopped · 1m wait · FHD", summary)
        assertFalse(summary.contains("-"))
        assertFalse(summary.contains("42"))
    }

    @Test
    fun `intervalMinutes 0 always reads as no wait`() {
        val summary = formatSessionPlan(
            clipSeconds = 30,
            loopCount = 10,
            intervalMinutes = 0,
            quality = "FHD"
        )
        assertFalse(summary.contains("0m"))
        assertFalse(summary.contains("0 m"))
        assertEquals("30s clips · 10 repeats · no wait · FHD", summary)
    }
}
