package com.aritr.rova.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B6 (front/back camera switch) — pure-policy tests for [LensFlipPolicy].
 *
 * Mirrors the single-source-of-truth guard that [RovaRecordingService.flipCamera]
 * delegates to. JVM-only per house test policy (the service wrapper stays a thin
 * seam, consistent with the existing untested `flipCamera`).
 */
class LensFlipPolicyTest {

    @Test
    fun portrait_idle_allowsFlip() {
        assertTrue(LensFlipPolicy.shouldAllowFlip(mode = "Portrait", isRecording = false))
    }

    @Test
    fun landscape_idle_allowsFlip() {
        assertTrue(LensFlipPolicy.shouldAllowFlip(mode = "Landscape", isRecording = false))
    }

    @Test
    fun portraitLandscape_idle_blocksFlip() {
        // P+L (DualShot) is rear-only by design — the flip must be a no-op.
        assertFalse(LensFlipPolicy.shouldAllowFlip(mode = "PortraitLandscape", isRecording = false))
    }

    @Test
    fun portrait_recording_blocksFlip() {
        // Lens cannot change mid-session — rebind would strand the active recording.
        assertFalse(LensFlipPolicy.shouldAllowFlip(mode = "Portrait", isRecording = true))
    }

    // B6 codex follow-up — front-camera availability guard ------------------

    @Test
    fun flipToFront_noFrontCamera_blocked() {
        // Toggling TO front on a device with no front sensor must be a no-op,
        // else the bind fails and the preview strands black (codex HIGH).
        assertFalse(
            LensFlipPolicy.shouldAllowFlip(
                mode = "Portrait", isRecording = false,
                targetIsFront = true, hasFrontCamera = false,
            )
        )
    }

    @Test
    fun flipToFront_hasFrontCamera_allowed() {
        assertTrue(
            LensFlipPolicy.shouldAllowFlip(
                mode = "Portrait", isRecording = false,
                targetIsFront = true, hasFrontCamera = true,
            )
        )
    }

    @Test
    fun flipToRear_noFrontCamera_allowed() {
        // Toggling back TO rear is always fine regardless of front availability.
        assertTrue(
            LensFlipPolicy.shouldAllowFlip(
                mode = "Portrait", isRecording = false,
                targetIsFront = false, hasFrontCamera = false,
            )
        )
    }

    @Test
    fun resolveIsFront_prefFrontButNoFrontCamera_resolvesRear() {
        // A stale "prefer front" pref on a front-less device must resolve rear.
        assertFalse(LensFlipPolicy.resolveIsFront(preferFront = true, hasFrontCamera = false))
    }

    @Test
    fun resolveIsFront_prefFrontWithFrontCamera_resolvesFront() {
        assertTrue(LensFlipPolicy.resolveIsFront(preferFront = true, hasFrontCamera = true))
    }

    @Test
    fun resolveIsFront_prefRear_resolvesRear() {
        assertFalse(LensFlipPolicy.resolveIsFront(preferFront = false, hasFrontCamera = true))
    }
}
