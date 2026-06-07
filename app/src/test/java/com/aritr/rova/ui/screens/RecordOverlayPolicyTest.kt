package com.aritr.rova.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug 3 — the "Initializing/Switching Camera" overlay must NOT flash on a warm
 * nav-return surface re-swap; it shows only during a genuine cold acquire or a
 * lens flip, and never while merging or without camera permission.
 */
class RecordOverlayPolicyTest {

    @Test fun warmReturn_noColdAcquire_noFlip_isHidden() {
        assertFalse(
            RecordOverlayPolicy.showInitializingOverlay(
                coldAcquire = false,
                flipInFlight = false,
                isMerging = false,
                hasCapturePermissions = true,
            )
        )
    }

    @Test fun genuineColdAcquire_isShown() {
        assertTrue(
            RecordOverlayPolicy.showInitializingOverlay(
                coldAcquire = true,
                flipInFlight = false,
                isMerging = false,
                hasCapturePermissions = true,
            )
        )
    }

    @Test fun lensFlipInFlight_isShown() {
        assertTrue(
            RecordOverlayPolicy.showInitializingOverlay(
                coldAcquire = false,
                flipInFlight = true,
                isMerging = false,
                hasCapturePermissions = true,
            )
        )
    }

    @Test fun merging_suppresses_evenDuringColdAcquire() {
        assertFalse(
            RecordOverlayPolicy.showInitializingOverlay(
                coldAcquire = true,
                flipInFlight = false,
                isMerging = true,
                hasCapturePermissions = true,
            )
        )
    }

    @Test fun missingPermission_suppresses_evenDuringColdAcquire() {
        assertFalse(
            RecordOverlayPolicy.showInitializingOverlay(
                coldAcquire = true,
                flipInFlight = true,
                isMerging = false,
                hasCapturePermissions = false,
            )
        )
    }

    @Test fun missingPermission_andFlip_stillSuppressed() {
        assertFalse(
            RecordOverlayPolicy.showInitializingOverlay(
                coldAcquire = false,
                flipInFlight = true,
                isMerging = false,
                hasCapturePermissions = false,
            )
        )
    }
}
