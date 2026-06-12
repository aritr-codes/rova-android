package com.aritr.rova.ui.screens.chrome

import android.view.Surface

/**
 * PR-ε (spec §2.3) — UI counter-rotation angle for a snapped Surface.ROTATION_*.
 *
 * NOT the CameraX targetRotation mapping (that one is INVERSE — physical 45-135°
 * maps to ROTATION_270; see research §2). Two named functions, never interchanged;
 * the capture side lives in the service (checkSetTargetRotationBoundaryOnly).
 *
 * Sign convention verified on device (plan Task 10 Step 3). If the spin is
 * inverted on hardware, flip the 90/-90 pair HERE ONLY.
 */
internal fun uiCounterRotationDegrees(snappedRotation: Int): Float = when (snappedRotation) {
    Surface.ROTATION_90 -> 90f
    Surface.ROTATION_180 -> 180f
    Surface.ROTATION_270 -> -90f
    else -> 0f
}

/**
 * PR-ε (research §3) — minimal signed delta from an UNWRAPPED accumulator angle
 * to a target (mod 360). Result in [-180, 180]; +180 chosen for the half-turn.
 * Animate `current + shortestPathDelta(current, target)` — never animate to the
 * raw target or a 270°→0° transition spins the long way.
 */
internal fun shortestPathDelta(currentUnwrapped: Float, targetMod360: Float): Float {
    val diff = (((targetMod360 - currentUnwrapped) % 360f) + 360f) % 360f  // [0,360)
    return if (diff > 180f) diff - 360f else diff
}
