package com.aritr.rova.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeReconfigurePolicyTest {

    @Test fun sameMode_cameraActive_skipsReconfigure() {
        assertTrue(
            ModeReconfigurePolicy.shouldSkipReconfigure(
                requestedMode = "Portrait",
                currentMode = "Portrait",
                isCameraActive = true,
                isFrontCamera = false
            )
        )
    }

    @Test fun differentMode_doesNotSkip() {
        assertFalse(
            ModeReconfigurePolicy.shouldSkipReconfigure(
                requestedMode = "Landscape",
                currentMode = "Portrait",
                isCameraActive = true,
                isFrontCamera = false
            )
        )
    }

    @Test fun sameMode_cameraInactive_doesNotSkip() {
        // Camera not bound — the reconfigure doubles as the (re)acquire, must run.
        assertFalse(
            ModeReconfigurePolicy.shouldSkipReconfigure(
                requestedMode = "Portrait",
                currentMode = "Portrait",
                isCameraActive = false,
                isFrontCamera = false
            )
        )
    }

    @Test fun sameMode_nonPL_frontCamera_stillSkips() {
        // A non-P+L mode on the front camera needs no selector snap → idempotent.
        assertTrue(
            ModeReconfigurePolicy.shouldSkipReconfigure(
                requestedMode = "Portrait",
                currentMode = "Portrait",
                isCameraActive = true,
                isFrontCamera = true
            )
        )
    }

    @Test fun samePL_frontCamera_doesNotSkip_selectorSnapRequired() {
        // Defensive: P+L requested while on the front camera must rebind to snap
        // the selector back to rear, even if the mode label is unchanged.
        assertFalse(
            ModeReconfigurePolicy.shouldSkipReconfigure(
                requestedMode = ModeReconfigurePolicy.MODE_PORTRAIT_LANDSCAPE,
                currentMode = ModeReconfigurePolicy.MODE_PORTRAIT_LANDSCAPE,
                isCameraActive = true,
                isFrontCamera = true
            )
        )
    }

    @Test fun samePL_rearCamera_skips() {
        assertTrue(
            ModeReconfigurePolicy.shouldSkipReconfigure(
                requestedMode = ModeReconfigurePolicy.MODE_PORTRAIT_LANDSCAPE,
                currentMode = ModeReconfigurePolicy.MODE_PORTRAIT_LANDSCAPE,
                isCameraActive = true,
                isFrontCamera = false
            )
        )
    }
}
