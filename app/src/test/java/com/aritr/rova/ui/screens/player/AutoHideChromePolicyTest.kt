package com.aritr.rova.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-7 — pins the auto-hide timer-gating + visibility rules. Pure,
 * Compose-free; the delay() itself stays in the LaunchedEffect (the helper
 * only decides whether the timer may run and the next visibility).
 */
class AutoHideChromePolicyTest {

    @Test fun `timeout is three seconds`() {
        assertEquals(3_000L, AutoHideChromePolicy.DEFAULT_TIMEOUT_MS)
    }

    @Test fun `timer runs only while playing, visible, not scrubbing, no menu`() {
        assertTrue(
            AutoHideChromePolicy.shouldRunHideTimer(
                isPlaying = true, isScrubbing = false, chromeVisible = true
            )
        )
    }

    @Test fun `timer suppressed when paused`() {
        assertFalse(
            AutoHideChromePolicy.shouldRunHideTimer(
                isPlaying = false, isScrubbing = false, chromeVisible = true
            )
        )
    }

    @Test fun `timer suppressed while scrubbing`() {
        assertFalse(
            AutoHideChromePolicy.shouldRunHideTimer(
                isPlaying = true, isScrubbing = true, chromeVisible = true
            )
        )
    }

    @Test fun `timer suppressed when already hidden`() {
        assertFalse(
            AutoHideChromePolicy.shouldRunHideTimer(
                isPlaying = true, isScrubbing = false, chromeVisible = false
            )
        )
    }

    @Test fun `timer suppressed when speed menu open`() {
        assertFalse(
            AutoHideChromePolicy.shouldRunHideTimer(
                isPlaying = true, isScrubbing = false, chromeVisible = true, speedMenuOpen = true
            )
        )
    }

    @Test fun `tap always shows chrome`() {
        assertTrue(AutoHideChromePolicy.onUserTap(currentlyVisible = false))
        assertTrue(AutoHideChromePolicy.onUserTap(currentlyVisible = true))
    }

    @Test fun `pause forces visible`() {
        assertTrue(AutoHideChromePolicy.onPlaybackPaused())
    }
}
