package com.aritr.rova.service.dualrecord.internal

import com.aritr.rova.service.dualrecord.VideoSide

/**
 * Phase 6.1a — `MediaMuxer.setOrientationHint(int)` value (in degrees,
 * one of 0/90/180/270) for each side.
 *
 * Phase 6.1b smoke-fix #4 — Path A: BOTH sides now return `displayRotation`
 * passthrough. The previous landscape-side `+90` was the "tag the file as
 * landscape and let the player rotate it back" path — which produced the
 * symptom owners saw on-device (a portrait-content frame stretched into a
 * landscape-aspect surface, then rotated 90° by the player → stretched
 * portrait at portrait aspect). Path A instead renders landscape-oriented
 * pixels directly into the landscape encoder surface (per-target UV
 * rotation in [EglRouter]); with correctly-oriented pixels in the file,
 * the rotation hint becomes a passthrough of the device's display
 * orientation, identical to the portrait side.
 *
 * The hint is consumed by [com.aritr.rova.service.dualrecord.internal.DualMuxer]
 * (`setOrientationHint(side, degrees)`) — NOT by `MediaFormat.KEY_ROTATION`
 * on the track. Per AOSP `MediaMuxer.addTrack` docs, MP4 ignores the
 * track-level `KEY_ROTATION` and reads the composition matrix from
 * `setOrientationHint` only. The encoder format also drops `KEY_ROTATION`
 * (some Qualcomm encoders honor it at bitstream level and would
 * double-rotate now that pixels are already oriented).
 *
 * Pinned-rotation invariant updated: the GL shader does not rotate by
 * itself — per-target `uTexMatrix` encodes any per-target rotation, and
 * the muxer's `setOrientationHint` agrees with the rendered orientation.
 * This still prevents double-rotation playback (spec §9 intent).
 */
internal object RotationCalculator {

    private val SURFACE_ROTATION_TO_DEGREES = intArrayOf(0, 90, 180, 270)

    fun tag(displayRotation: Int, side: VideoSide): Int {
        require(displayRotation in 0..3) {
            "displayRotation must be Surface.ROTATION_0..ROTATION_270 (0..3), was $displayRotation"
        }
        // Both sides: passthrough display rotation. The encoder bitstream
        // already carries correctly-oriented pixels for that side after
        // the EglRouter per-target UV rotation, so the muxer hint is
        // identical for both — there is no asymmetry to encode in
        // metadata. `side` retained on the signature so future
        // sensor-orientation-aware paths (e.g. front-camera-specific
        // landscape framing) can branch without an API churn.
        return when (side) {
            VideoSide.PORTRAIT, VideoSide.LANDSCAPE -> SURFACE_ROTATION_TO_DEGREES[displayRotation]
        }
    }
}
