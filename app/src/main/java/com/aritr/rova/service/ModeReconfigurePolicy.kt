package com.aritr.rova.service

/**
 * Idempotent mode-switch guard for [RovaRecordingService.setMode].
 *
 * A `setMode` call that does not change the bound camera use-case config must
 * NOT pay a full `forceReconfigureCamera()` unbind/rebind — on device that
 * costs a ~2s camera-ready gap (observed as a burst of redundant rebinds when
 * the mode tab is re-tapped or the call is re-emitted). Tapping the
 * already-selected mode is the common case this skips.
 *
 * Pure so it is unit-testable under `isReturnDefaultValues = true`; the service
 * wrapper stays a thin seam (house pure-helper-extraction pattern, see
 * `CameraReleasePolicy`, `SegmentGateThermal`).
 */
internal object ModeReconfigurePolicy {
    /** ADR-0029 PR-γ — topology string for dual-lens single-device recording. */
    const val MODE_DUALSHOT = "DualShot"

    /**
     * True when [requestedMode] is already the bound mode and no rebind is
     * required. A rebind IS still required (returns false) when:
     *  - the mode actually changes (`requestedMode != currentMode`), or
     *  - the camera is not currently bound (`!isCameraActive`) — the
     *    reconfigure doubles as the (re)acquire, so it must run, or
     *  - selecting DualShot while on the front camera (`isFrontCamera`), which
     *    must snap back to the rear selector and therefore rebind (mirrors the
     *    selector-snap branch in `setMode`).
     */
    fun shouldSkipReconfigure(
        requestedMode: String,
        currentMode: String,
        isCameraActive: Boolean,
        isFrontCamera: Boolean
    ): Boolean {
        val needsSelectorSnap = requestedMode == MODE_DUALSHOT && isFrontCamera
        return requestedMode == currentMode && isCameraActive && !needsSelectorSnap
    }
}
