package com.aritr.rova.service.audio

import com.aritr.rova.data.AudioMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the truth table for [shouldPlayBeep]. Six meaningful cells:
 * three audioMode/interval combinations × beeps-on / beeps-off.
 *
 * The on-device smoke that drove the corrective fix observed
 * `R.raw.rova_beep` getting re-captured into a VIDEO_AUDIO clip with
 * a non-zero interval. Bleed-prevention for that case is timing
 * (suspend-await playback + tail margin at the call site) — the
 * policy still allows the beep. The policy's only suppression is
 * for VIDEO_AUDIO + interval==0 where awaited playback would punch
 * a gap into continuous mode.
 */
class BeepPolicyTest {

    @Test
    fun `VIDEO_ONLY with beeps on plays beep regardless of interval`() {
        assertTrue(shouldPlayBeep(enableBeeps = true, audioMode = AudioMode.VIDEO_ONLY, intervalMinutes = 1))
        assertTrue(shouldPlayBeep(enableBeeps = true, audioMode = AudioMode.VIDEO_ONLY, intervalMinutes = 0))
    }

    @Test
    fun `VIDEO_ONLY with beeps off is silent`() {
        assertFalse(shouldPlayBeep(enableBeeps = false, audioMode = AudioMode.VIDEO_ONLY, intervalMinutes = 1))
        assertFalse(shouldPlayBeep(enableBeeps = false, audioMode = AudioMode.VIDEO_ONLY, intervalMinutes = 0))
    }

    @Test
    fun `VIDEO_AUDIO with non-zero interval plays beep`() {
        // Bleed-prevention is timing-based at the call site, not policy.
        assertTrue(shouldPlayBeep(enableBeeps = true, audioMode = AudioMode.VIDEO_AUDIO, intervalMinutes = 1))
        assertTrue(shouldPlayBeep(enableBeeps = true, audioMode = AudioMode.VIDEO_AUDIO, intervalMinutes = 60))
    }

    @Test
    fun `VIDEO_AUDIO with zero interval is silent`() {
        // Continuous capture has no natural gap; awaited playback
        // would interrupt back-to-back recording, so the audible cue
        // is dropped entirely for this combination.
        assertFalse(shouldPlayBeep(enableBeeps = true, audioMode = AudioMode.VIDEO_AUDIO, intervalMinutes = 0))
    }

    @Test
    fun `VIDEO_AUDIO with beeps off is silent in every interval`() {
        assertFalse(shouldPlayBeep(enableBeeps = false, audioMode = AudioMode.VIDEO_AUDIO, intervalMinutes = 0))
        assertFalse(shouldPlayBeep(enableBeeps = false, audioMode = AudioMode.VIDEO_AUDIO, intervalMinutes = 1))
    }
}
