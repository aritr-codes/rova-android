package com.aritr.rova.ui.screens

import com.aritr.rova.ui.theme.RovaGlyph
import com.aritr.rova.ui.theme.RovaIcons

/**
 * Canonical capture-topology → bespoke glyph (ADR-0031 §2 — one concept → one glyph).
 * Auto (Single capture) uses the lone-frame [RovaIcons.Single]; the dual topologies use
 * their twin/PiP marks. Pure so the picker wiring stays JVM-testable.
 */
internal fun captureGlyphFor(mode: CaptureMode): RovaGlyph = when (mode) {
    CaptureMode.Auto -> RovaIcons.Single
    CaptureMode.DualShot -> RovaIcons.DualShot
    CaptureMode.DualSight -> RovaIcons.DualSight
}

/**
 * Orientation-policy option → bespoke glyph. The picker rows are built from
 * (policy, lockRotation) pairs (see [OrientationRow]): FollowDevice, Lock@0 = portrait,
 * Lock@{1,3} = landscape.
 */
internal fun orientationGlyphFor(optPolicy: String, optLock: Int): RovaGlyph = when {
    optPolicy == "FollowDevice" -> RovaIcons.FollowDevice
    optLock == 0 -> RovaIcons.OrientationPortrait
    else -> RovaIcons.OrientationLandscape
}
