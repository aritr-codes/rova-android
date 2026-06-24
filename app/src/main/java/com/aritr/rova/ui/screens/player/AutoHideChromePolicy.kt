package com.aritr.rova.ui.screens.player

/**
 * PR-7 — pure auto-hide chrome policy. The `delay()` itself stays in the
 * screen's LaunchedEffect (Compose/coroutine side); this helper only decides
 * *whether* the hide timer may run and what the next visibility is, keeping
 * the wrapper a thin seam (house pattern).
 *
 * Chrome (top bar + bottom panel) fades out after [DEFAULT_TIMEOUT_MS] of
 * inactivity WHILE PLAYING. It is pinned visible when paused (a reviewer
 * studying a frame), while scrubbing, or (variant B only) while a speed menu
 * is open. Owner: Q5 tap = always-show, Q7 timeout = 3 s.
 */
object AutoHideChromePolicy {

    const val DEFAULT_TIMEOUT_MS = 3_000L

    /** The hide countdown may run only when ALL of these hold. */
    fun shouldRunHideTimer(
        isPlaying: Boolean,
        isScrubbing: Boolean,
        chromeVisible: Boolean,
        speedMenuOpen: Boolean = false
    ): Boolean = isPlaying && !isScrubbing && chromeVisible && !speedMenuOpen

    /** Owner Q5 — a tap always shows chrome (never hides on tap). */
    fun onUserTap(currentlyVisible: Boolean): Boolean = true

    /** Pausing always reveals controls. */
    fun onPlaybackPaused(): Boolean = true
}
