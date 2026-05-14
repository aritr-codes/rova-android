package com.aritr.rova.service.dualrecord.internal

import com.aritr.rova.service.dualrecord.VideoSide

/**
 * Phase 6.1a — `MediaFormat.KEY_ROTATION` integer for each side.
 *
 * Aligns with Phase 6's `computeTargetRotation(displayRotation, mode)`
 * (top-level helper in `RovaRecordingService.kt`): portrait side = display
 * rotation passthrough (in degrees); landscape side = display rotation +
 * quarter-turn clockwise, wrapping mod 360.
 *
 * Player-side rotation tagging only — the GL shader does NOT rotate
 * pixels (see spec §9: pinned single-source-of-truth to prevent
 * double-rotation playback).
 */
internal object RotationCalculator {

    private val SURFACE_ROTATION_TO_DEGREES = intArrayOf(0, 90, 180, 270)

    fun tag(displayRotation: Int, side: VideoSide): Int {
        require(displayRotation in 0..3) {
            "displayRotation must be Surface.ROTATION_0..ROTATION_270 (0..3), was $displayRotation"
        }
        val base = SURFACE_ROTATION_TO_DEGREES[displayRotation]
        return when (side) {
            VideoSide.PORTRAIT -> base
            VideoSide.LANDSCAPE -> (base + 90) % 360
        }
    }
}
