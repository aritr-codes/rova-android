package com.aritr.rova.ui.screens

/**
 * ADR-0029 (P+L portrait-lock · codex review 2026-06-10) — pure decision seam for
 * the "defer the P+L camera rebind until the window is portrait" rule.
 *
 * DualShot/P+L is a portrait-HELD mode: a single 4:3 sensor source is fanned out
 * through EGL14/GLES to two cropped preview surfaces (ADR-0009). Those preview
 * `TextureView`s are owned by Compose. Committing the mode switch to P+L also forces
 * the window to portrait (`requestedOrientation`), so selecting P+L from a LANDSCAPE
 * Single window binds the DualShot EGL pipeline CONCURRENTLY with the landscape→
 * portrait rotation. The rotation relayout recreates/destroys the preview surfaces
 * mid-bind → `EGL_BAD_SURFACE`, the `CameraEffect` ends up "Unused", and only one of
 * the two P+L panes renders (device-confirmed, Screenshot_20260610_122450).
 *
 * Fix (codex option B): keep Single bound through the rotation and commit P+L — the
 * camera rebind — only once the window has actually settled to portrait, so the
 * DualShot surfaces are born portrait and never churned. This helper is the single
 * predicate; the orientation request itself is driven off the desired mode in
 * [RecordScreen] so the window starts rotating immediately.
 *
 * Tested by [com.aritr.rova.ui.screens.DualShotPortraitGateTest] (pure JVM).
 * Pure-helper test-seam precedent: [cycleModeNext], [loopPillContent].
 */
internal object DualShotPortraitGate {
    const val P_L: String = "PortraitLandscape"

    /**
     * True when committing [target] now would bind the DualShot EGL pipeline while
     * the window is mid-rotation to portrait. Only the landscape→P+L transition is
     * unsafe; every other transition (any mode while already portrait, or leaving
     * P+L) commits immediately.
     */
    fun shouldDefer(target: String, isPortrait: Boolean): Boolean =
        target == P_L && !isPortrait
}
