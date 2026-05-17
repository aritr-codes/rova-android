package com.aritr.rova.service.dualrecord.internal

import com.aritr.rova.service.dualrecord.VideoSide

/**
 * Phase 6.1a → smoke-fix #5 — `MediaMuxer.setOrientationHint(int)` value
 * (in degrees, one of 0/90/180/270) for each side.
 *
 * **Returns 0 for both sides at every `displayRotation`.** The
 * [EglRouter]'s per-frame `computeCropMatrix` already pre-rotates pixels
 * to match each target's aspect orientation (rotate iff consumer and
 * target disagree on landscape-vs-portrait), so each encoder file
 * contains upright pixels in its native aspect regardless of how the
 * device was held when recording started. A non-zero hint here would
 * tell the player to rotate already-correct pixels, producing exactly
 * the "stretched/rotated playback" the per-frame UV crop was introduced
 * to fix.
 *
 * History:
 *  - 6.1a: landscape side returned `(displayRotation + 90) % 360` — the
 *    "tag the file as landscape and let the player rotate" approach.
 *    Failed on devices whose hw encoder honored `KEY_ROTATION` at the
 *    bitstream level (double-rotation → stretched portrait).
 *  - Smoke-fix #4: both sides returned `displayRotation` passthrough;
 *    relied on the per-side static `+90°` UV rotation only for the
 *    LANDSCAPE side. Worked only when the phone was held portrait —
 *    phone-landscape made the consumer surface landscape, the static
 *    `+90°` then over-rotated, and the landscape video came out
 *    severely stretched.
 *  - Smoke-fix #5 (this revision): the [EglRouter]'s cropMatrix is now
 *    consumer-aspect-aware. Pre-rotated pixels are always upright →
 *    hint = 0 for both sides.
 *
 * The hint is consumed by [com.aritr.rova.service.dualrecord.internal.DualMuxer]
 * (`setOrientationHint(side, degrees)`) — NOT by `MediaFormat.KEY_ROTATION`
 * on the track. Per AOSP `MediaMuxer.addTrack` docs, MP4 ignores the
 * track-level `KEY_ROTATION` and reads the composition matrix from
 * `setOrientationHint` only. The encoder format also drops `KEY_ROTATION`
 * (some Qualcomm encoders honor it at bitstream level and would
 * double-rotate now that pixels are already oriented).
 *
 * `displayRotation` and `side` parameters are retained on the API for
 * future sensor-orientation-aware paths (e.g. lens-facing-specific
 * framing) without churn.
 */
internal object RotationCalculator {

    @Suppress("UNUSED_PARAMETER")
    fun tag(displayRotation: Int, side: VideoSide): Int {
        require(displayRotation in 0..3) {
            "displayRotation must be Surface.ROTATION_0..ROTATION_270 (0..3), was $displayRotation"
        }
        return 0
    }
}
