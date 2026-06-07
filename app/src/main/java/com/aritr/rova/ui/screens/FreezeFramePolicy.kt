package com.aritr.rova.ui.screens

/**
 * Bug 3 — pure decision seam for the record-screen freeze-frame overlay.
 *
 * House convention (pure-helper extraction): the framework-touching composable
 * stays a thin seam; this object holds the unit-testable show/hide logic so it
 * runs under `isReturnDefaultValues = true`.
 *
 * Invariant (Bug 3): on a warm tab-return the camera stays bound (ADR-0021) but
 * the PreviewView is recreated, so CameraX must re-cycle a SurfaceRequest onto
 * the fresh surface (~1 s black). [shouldShowFreezeFrame] covers that gap with
 * the last captured frame instead of bare black. It must NOT compete with the
 * genuine cold-acquire "Initializing Camera" overlay ([RecordOverlayPolicy]),
 * which owns the real cold-bind window — hence the `coldAcquireInProgress`
 * exclusion. Single-mode PreviewView only; P+L (DualPreviewZone) is out of
 * scope.
 */
object FreezeFramePolicy {
    /**
     * Show the last captured preview frame to bridge the warm-return surface
     * re-swap gap (Bug 3). Only when we HAVE a frame, the new surface is NOT
     * yet streaming, we're not merging, and not during a genuine cold acquire
     * (the Initializing overlay owns that).
     *
     * @param hasStashedFrame        a previously captured frame is held.
     * @param streaming              the new surface is already STREAMING — the
     *                               real preview is up, so drop the frame.
     * @param isMerging              a merge is running — the merge HUD owns the
     *                               screen.
     * @param coldAcquireInProgress  a genuine cold camera bind is in flight —
     *                               the Initializing overlay owns that window.
     */
    fun shouldShowFreezeFrame(
        hasStashedFrame: Boolean,
        streaming: Boolean,
        isMerging: Boolean,
        coldAcquireInProgress: Boolean,
    ): Boolean = hasStashedFrame && !streaming && !isMerging && !coldAcquireInProgress
}
