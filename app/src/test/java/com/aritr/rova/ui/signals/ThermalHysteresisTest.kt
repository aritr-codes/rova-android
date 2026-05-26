package com.aritr.rova.ui.signals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Milestone 3 (ADR-0019) — pure-JVM tests for [applyThermalHysteresis].
 * Covers the 10 cases enumerated in spec §7.1. No Android, no coroutines,
 * no Compose — same shape as
 * [com.aritr.rova.service.recovery.MergeFailureClassTest],
 * [com.aritr.rova.service.recovery.MergeRetryPolicyTest], and
 * [com.aritr.rova.ui.screens.RecordingFrameLayoutTest].
 */
class ThermalHysteresisTest {

    @Test fun `rise_instant_no_dwell — raw above stable transitions immediately`() {
        val current = HysteresisState(stable = ThermalStatus.MODERATE, dwellEnteredAtMs = null)
        val result = applyThermalHysteresis(
            raw = ThermalStatus.SEVERE,
            current = current,
            nowMs = 1000L,
        )
        assertEquals(ThermalStatus.SEVERE, result.stable)
        assertNull("rise clears any pending dwell", result.dwellEnteredAtMs)
    }

    @Test fun `rise_clears_inflight_dwell — raw above stable during fall-dwell discards dwell`() {
        val current = HysteresisState(stable = ThermalStatus.CRITICAL, dwellEnteredAtMs = 500L)
        val result = applyThermalHysteresis(
            raw = ThermalStatus.EMERGENCY,
            current = current,
            nowMs = 1500L,
        )
        assertEquals(ThermalStatus.EMERGENCY, result.stable)
        assertNull("in-flight dwell discarded on rise", result.dwellEnteredAtMs)
    }

    @Test fun `fall_starts_dwell — lower raw with no prior dwell starts timer`() {
        val current = HysteresisState(stable = ThermalStatus.SEVERE, dwellEnteredAtMs = null)
        val result = applyThermalHysteresis(
            raw = ThermalStatus.MODERATE,
            current = current,
            nowMs = 2000L,
        )
        assertEquals("stable unchanged during dwell", ThermalStatus.SEVERE, result.stable)
        assertEquals("dwell timer recorded", 2000L, result.dwellEnteredAtMs)
    }

    @Test fun `fall_holds_during_dwell — same lower raw mid-dwell holds timer`() {
        val current = HysteresisState(stable = ThermalStatus.SEVERE, dwellEnteredAtMs = 1000L)
        val result = applyThermalHysteresis(
            raw = ThermalStatus.MODERATE,
            current = current,
            nowMs = 2500L,
            fallDwellMs = 3_000L,
        )
        assertEquals(ThermalStatus.SEVERE, result.stable)
        assertEquals("timer not restarted on further lower-raw events", 1000L, result.dwellEnteredAtMs)
    }

    @Test fun `fall_holds_at_lower_raw_during_dwell — even lower raw mid-dwell still holds`() {
        val current = HysteresisState(stable = ThermalStatus.CRITICAL, dwellEnteredAtMs = 1000L)
        val result = applyThermalHysteresis(
            raw = ThermalStatus.LIGHT,
            current = current,
            nowMs = 2500L,
            fallDwellMs = 3_000L,
        )
        assertEquals("multi-level drop during dwell still holds at stable", ThermalStatus.CRITICAL, result.stable)
        assertEquals("original timer preserved", 1000L, result.dwellEnteredAtMs)
    }

    @Test fun `fall_completes_after_dwell_one_step_down — dwell expiry transitions exactly one level`() {
        val current = HysteresisState(stable = ThermalStatus.SEVERE, dwellEnteredAtMs = 1000L)
        val result = applyThermalHysteresis(
            raw = ThermalStatus.LIGHT,
            current = current,
            nowMs = 4000L,
            fallDwellMs = 3_000L,
        )
        assertEquals("step down ONE level (SEVERE -> MODERATE), not all the way to LIGHT",
            ThermalStatus.MODERATE, result.stable)
        assertNull("dwell timer cleared after expiry", result.dwellEnteredAtMs)
    }

    @Test fun `fall_completes_step_down_multi_drop_raw — multi-level raw stays one-step per dwell`() {
        // Simulate dwell expire with raw still at very low level — only ONE step transitions.
        val current = HysteresisState(stable = ThermalStatus.CRITICAL, dwellEnteredAtMs = 100L)
        val result = applyThermalHysteresis(
            raw = ThermalStatus.NONE,
            current = current,
            nowMs = 3100L,
            fallDwellMs = 3_000L,
        )
        assertEquals("CRITICAL -> SEVERE on first dwell, NOT CRITICAL -> NONE",
            ThermalStatus.SEVERE, result.stable)
        assertNull(result.dwellEnteredAtMs)
    }

    @Test fun `equal_raw_during_dwell_clears_dwell — raw equal to stable mid-dwell aborts dwell`() {
        val current = HysteresisState(stable = ThermalStatus.SEVERE, dwellEnteredAtMs = 1000L)
        val result = applyThermalHysteresis(
            raw = ThermalStatus.SEVERE,
            current = current,
            nowMs = 2500L,
        )
        assertEquals(ThermalStatus.SEVERE, result.stable)
        assertNull("equal-to-stable mid-dwell clears the dwell (raw bounced back)", result.dwellEnteredAtMs)
    }

    @Test fun `boundary_thrash_stays_stable — MOD-SEV-MOD-SEV flap collapses to stable plus restarted dwell`() {
        // Initial state: stable=SEVERE, no dwell.
        var s = HysteresisState(stable = ThermalStatus.SEVERE, dwellEnteredAtMs = null)
        // t=0: raw=MODERATE -> starts dwell.
        s = applyThermalHysteresis(ThermalStatus.MODERATE, s, nowMs = 0L)
        assertEquals(ThermalStatus.SEVERE, s.stable)
        assertEquals(0L, s.dwellEnteredAtMs)
        // t=500: raw=SEVERE -> clears dwell (equal-to-stable).
        s = applyThermalHysteresis(ThermalStatus.SEVERE, s, nowMs = 500L)
        assertEquals(ThermalStatus.SEVERE, s.stable)
        assertNull(s.dwellEnteredAtMs)
        // t=1000: raw=MODERATE -> starts NEW dwell (timer reset to 1000).
        s = applyThermalHysteresis(ThermalStatus.MODERATE, s, nowMs = 1000L)
        assertEquals(ThermalStatus.SEVERE, s.stable)
        assertEquals("new dwell starts after clear", 1000L, s.dwellEnteredAtMs)
    }

    @Test fun `defensive_negative_now_does_not_crash — negative nowMs is recorded as-is`() {
        val current = HysteresisState(stable = ThermalStatus.CRITICAL, dwellEnteredAtMs = null)
        val result = applyThermalHysteresis(
            raw = ThermalStatus.SEVERE,
            current = current,
            nowMs = -1L,
        )
        assertEquals(ThermalStatus.CRITICAL, result.stable)
        assertEquals(-1L, result.dwellEnteredAtMs)
    }
}
