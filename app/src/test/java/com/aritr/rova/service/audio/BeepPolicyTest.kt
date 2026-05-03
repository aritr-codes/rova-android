package com.aritr.rova.service.audio

import com.aritr.rova.data.AudioMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the audio-bleed regression: the on-device smoke that drove
 * this fix showed `R.raw.rova_beep` getting re-captured into the
 * recorded clip on a `VIDEO_AUDIO` session. The policy below is the
 * single seam that decides whether the speaker beep runs at all,
 * and it must stay suppressed for every audio-capturing session
 * regardless of the user's preferences toggle.
 */
class BeepPolicyTest {

    @Test
    fun `VIDEO_ONLY with beeps enabled plays beep`() {
        assertTrue(shouldPlayBeep(enableBeeps = true, audioMode = AudioMode.VIDEO_ONLY))
    }

    @Test
    fun `VIDEO_ONLY with beeps disabled is silent`() {
        assertFalse(shouldPlayBeep(enableBeeps = false, audioMode = AudioMode.VIDEO_ONLY))
    }

    @Test
    fun `VIDEO_AUDIO with beeps enabled is silent to avoid mic bleed`() {
        // Regression case. Pre-fix returned true here; the speaker
        // tail bled into the next segment because recordSegment kicks
        // off the recorder immediately after the start-beep call site.
        assertFalse(shouldPlayBeep(enableBeeps = true, audioMode = AudioMode.VIDEO_AUDIO))
    }

    @Test
    fun `VIDEO_AUDIO with beeps disabled is silent`() {
        assertFalse(shouldPlayBeep(enableBeeps = false, audioMode = AudioMode.VIDEO_AUDIO))
    }
}
