package com.aritr.rova.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug 3 — the freeze-frame bridges the warm tab-return surface re-swap gap. It
 * shows only when a frame is held, the new surface is not yet streaming, no
 * merge is running, and no genuine cold acquire is in flight (that window
 * belongs to the Initializing overlay).
 */
class FreezeFramePolicyTest {

    @Test fun warmReturn_frameHeld_notStreaming_isShown() {
        assertTrue(
            FreezeFramePolicy.shouldShowFreezeFrame(
                hasStashedFrame = true,
                streaming = false,
                isMerging = false,
                coldAcquireInProgress = false,
            )
        )
    }

    @Test fun streaming_dropsFrame() {
        assertFalse(
            FreezeFramePolicy.shouldShowFreezeFrame(
                hasStashedFrame = true,
                streaming = true,
                isMerging = false,
                coldAcquireInProgress = false,
            )
        )
    }

    @Test fun noFrame_isHidden() {
        assertFalse(
            FreezeFramePolicy.shouldShowFreezeFrame(
                hasStashedFrame = false,
                streaming = false,
                isMerging = false,
                coldAcquireInProgress = false,
            )
        )
    }

    @Test fun merging_suppresses() {
        assertFalse(
            FreezeFramePolicy.shouldShowFreezeFrame(
                hasStashedFrame = true,
                streaming = false,
                isMerging = true,
                coldAcquireInProgress = false,
            )
        )
    }

    @Test fun coldAcquire_suppresses_initializingOverlayOwnsIt() {
        assertFalse(
            FreezeFramePolicy.shouldShowFreezeFrame(
                hasStashedFrame = true,
                streaming = false,
                isMerging = false,
                coldAcquireInProgress = true,
            )
        )
    }

    @Test fun noFrame_evenWhenOtherwiseEligible_isHidden() {
        // Belt-and-suspenders: with every other gate open, the missing frame
        // alone keeps it hidden.
        assertFalse(
            FreezeFramePolicy.shouldShowFreezeFrame(
                hasStashedFrame = false,
                streaming = true,
                isMerging = true,
                coldAcquireInProgress = true,
            )
        )
    }
}
