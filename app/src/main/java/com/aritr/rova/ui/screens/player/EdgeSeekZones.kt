package com.aritr.rova.ui.screens.player

/**
 * PR-7 — pure tap-x → seek zone for double-tap edge-seek on the player
 * video surface. Compose-free, JVM-unit-testable.
 *
 * Double-tap the left third → seek back 10 s; right third → forward 10 s;
 * center → toggle play/pause (so the center isn't a dead zone). Single-tap
 * (show chrome) is handled separately by `detectTapGestures(onTap = …)`.
 *
 * Bands (half-open so every x maps to exactly one zone):
 *   [0, leftEdge)        → SEEK_BACK
 *   [leftEdge, rightEdge) → TOGGLE
 *   [rightEdge, width]   → SEEK_FORWARD
 * Degenerate width (≤0) or negative tapX → TOGGLE (safe, no divide-by-zero).
 */
object EdgeSeekZones {

    enum class Zone { SEEK_BACK, TOGGLE, SEEK_FORWARD }

    fun zoneFor(
        tapX: Float,
        width: Float,
        leftFraction: Float = 1f / 3f,
        rightFraction: Float = 1f / 3f
    ): Zone {
        if (width <= 0f || tapX < 0f) return Zone.TOGGLE
        val leftEdge = width * leftFraction
        val rightEdge = width * (1f - rightFraction)
        return when {
            tapX < leftEdge -> Zone.SEEK_BACK
            tapX >= rightEdge -> Zone.SEEK_FORWARD
            else -> Zone.TOGGLE
        }
    }
}
