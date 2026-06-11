package com.aritr.rova.service

/**
 * B6 (front/back camera switch) — single-source-of-truth flip guard for
 * [RovaRecordingService.flipCamera].
 *
 * A lens flip is only legal when:
 *  - the mode is NOT P+L (DualShot is rear-only — it binds the rear sensor's
 *    full-FOV 4:3 source and software-crops to portrait + landscape; the front
 *    path is not productionised and entry-level devices can't run concurrent
 *    rear+front streams), and
 *  - no recording is in flight (a rebind mid-session would strand the active
 *    `Recording`), and
 *  - the flip target is reachable: toggling TO the front lens requires the
 *    device to actually have a front camera (codex review — persisting a front
 *    choice that cannot bind strands the preview black and self-perpetuates
 *    across launches). Toggling back to rear is always allowed.
 *
 * Pure so it is unit-testable under `isReturnDefaultValues = true`; the service
 * wrapper stays a thin seam (house pure-helper-extraction pattern, see
 * [ModeReconfigurePolicy], `SegmentGateThermal`).
 */
internal object LensFlipPolicy {
    /**
     * @param targetIsFront the lens the flip would switch TO (default `false`
     *   keeps the original 2-arg call sites/tests valid).
     * @param hasFrontCamera whether the device exposes a front sensor (only
     *   consulted when [targetIsFront]).
     */
    fun shouldAllowFlip(
        mode: String,
        isRecording: Boolean,
        targetIsFront: Boolean = false,
        hasFrontCamera: Boolean = true,
    ): Boolean =
        mode != ModeReconfigurePolicy.MODE_DUALSHOT &&
            !isRecording &&
            (!targetIsFront || hasFrontCamera)

    /**
     * Resolve the EFFECTIVE lens for a single-mode bind from the persisted
     * preference: front only when the user chose it AND the device can deliver
     * it. Used to seed the selector at service start and to restore it when
     * leaving P+L, so a stale "prefer front" pref on a front-less device
     * self-heals to rear instead of stranding the preview.
     */
    fun resolveIsFront(preferFront: Boolean, hasFrontCamera: Boolean): Boolean =
        preferFront && hasFrontCamera
}
