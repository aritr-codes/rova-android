package com.aritr.rova.ui.screens

/**
 * Bug 3 — pure decision seam for the record-screen "Initializing / Switching
 * Camera" loading overlay.
 *
 * House convention (pure-helper extraction): the framework-touching composable
 * stays a thin seam; this object holds the unit-testable show/hide logic so it
 * runs under `isReturnDefaultValues = true`.
 *
 * Invariant (Bug 3): the overlay is driven by a REAL service cold-acquire
 * (`coldAcquire`, published by RovaRecordingService.markColdAcquire while a bind
 * is in flight) or an in-flight lens flip (`flipInFlight`) — NEVER by an
 * `isCameraActive` composition edge. RecordScreen leaves composition on tab-away
 * and recreates its PreviewView on return; the brief warm-return surface re-swap
 * gap must NOT flash a spinner now that the service keeps the camera warm
 * (ADR-0021). Dropping the old bare `!isCameraActive` term is the whole point.
 */
object RecordOverlayPolicy {
    /**
     * Whether to show the "Initializing/Switching Camera" overlay.
     *
     * @param coldAcquire           a genuine cold camera bind is in flight
     *                              (service `coldAcquireInProgress`).
     * @param flipInFlight          a front/back lens flip is rebinding (B6).
     * @param isMerging             a merge is running — suppress the overlay so
     *                              the merge HUD owns the screen.
     * @param hasCapturePermissions camera permission present — without it there
     *                              is no camera to initialize and the hard-block
     *                              sheet already explains why.
     */
    fun showInitializingOverlay(
        coldAcquire: Boolean,
        flipInFlight: Boolean,
        isMerging: Boolean,
        hasCapturePermissions: Boolean,
    ): Boolean = hasCapturePermissions && !isMerging && (coldAcquire || flipInFlight)
}
