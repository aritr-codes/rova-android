package com.aritr.rova.service.orientation

/**
 * PR-α (ADR-0029 §Decision 3) — pure HUD state. The current segment records at
 * [currentSegmentRotation]; if the device has since snapped to a different
 * rotation, [pendingNextRotation] differs and [rotatingNextClip] is true so the
 * HUD can tell the operator the NEXT clip will rotate (the Recorder ignores
 * mid-clip rotation changes, so the current clip stays as-is — spec §7 Risks).
 */
internal data class OrientationHudState(
    val currentSegmentRotation: Int,   // Surface.ROTATION_* frozen at this segment's start
    val pendingNextRotation: Int,      // latest stable snapped rotation
    val rotatingNextClip: Boolean,     // pendingNextRotation != currentSegmentRotation
)

/** Pure builder. [rotatingNextClip] = the two rotations differ. */
internal fun orientationHud(
    currentSegmentRotation: Int,
    pendingNextRotation: Int,
): OrientationHudState = OrientationHudState(
    currentSegmentRotation = currentSegmentRotation,
    pendingNextRotation = pendingNextRotation,
    rotatingNextClip = currentSegmentRotation != pendingNextRotation,
)
