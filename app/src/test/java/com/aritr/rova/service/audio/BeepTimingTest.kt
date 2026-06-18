package com.aritr.rova.service.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [beepPlaybackCeilingMs] — the `withTimeoutOrNull` ceiling that
 * `beepStart()` uses while awaiting the start cue's `onCompletion`.
 *
 * Why this helper exists (cue-bleed bug, 2026-06-18, device RZCYA1VBQ2H):
 * the previous code used a fixed 1500 ms ceiling premised on a stale
 * KDoc claim that `R.raw.rova_beep` is "~300 ms". On-device measurement
 * proved the asset is a ~3527 ms, 4-pulse cue, so the 1500 ms timeout
 * always tripped ~2 s before `onCompletion`, `beepStart` returned mid-cue,
 * and the recorder opened the mic while ~3 pulses were still playing →
 * the cue bled into the saved clip. The ceiling must be derived from the
 * real cue duration so the await actually reaches `onCompletion`.
 */
class BeepTimingTest {

    @Test
    fun `ceiling exceeds the real cue duration so the await reaches onCompletion`() {
        // Measured rova_beep duration on device.
        assertEquals(3527L + BEEP_CEILING_SLACK_MS, beepPlaybackCeilingMs(3527L))
        // Comfortably past onCompletion (which lagged the nominal end by ~440 ms).
        assertTrue(beepPlaybackCeilingMs(3527L) > 3527L + 440L)
    }

    @Test
    fun `short cue gets duration plus slack`() {
        assertEquals(300L + BEEP_CEILING_SLACK_MS, beepPlaybackCeilingMs(300L))
    }

    @Test
    fun `unknown duration falls back to the safe max ceiling`() {
        assertEquals(BEEP_CEILING_MAX_MS, beepPlaybackCeilingMs(0L))
        assertEquals(BEEP_CEILING_MAX_MS, beepPlaybackCeilingMs(-1L))
    }

    @Test
    fun `ceiling is clamped to the sane max backstop`() {
        assertEquals(BEEP_CEILING_MAX_MS, beepPlaybackCeilingMs(20_000L))
        // Boundary: duration + slack exactly at max is not clamped.
        assertEquals(BEEP_CEILING_MAX_MS, beepPlaybackCeilingMs(BEEP_CEILING_MAX_MS - BEEP_CEILING_SLACK_MS))
        // One past the boundary clamps.
        assertEquals(BEEP_CEILING_MAX_MS, beepPlaybackCeilingMs(BEEP_CEILING_MAX_MS - BEEP_CEILING_SLACK_MS + 1))
    }
}
