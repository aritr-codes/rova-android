package com.aritr.rova.ui.screens.chrome

/** Which horizontal edge landscape chrome hugs — the system-nav-bar edge. */
enum class NavEdge { Leading, Trailing }

/** Landscape system-nav-bar insets in px (only left/right matter here). */
data class NavBarInsetsPx(val left: Int, val right: Int)

/**
 * ADR-0029 §B (PR-β, codex thread 019eb14d). Pure decision: which edge the nav
 * rail / settings sheet / config card hug. Primary signal is the navigationBars
 * inset side (layout truth) — right inset => 3-button nav on the right
 * (ROTATION_90) => Trailing; left inset => ROTATION_270 => Leading. Gesture nav
 * has no side inset (both 0) => default Trailing.
 *
 * Tested by [SystemNavEdgeTest] (pure JVM).
 */
fun systemNavEdge(insets: NavBarInsetsPx): NavEdge = when {
    insets.right > insets.left -> NavEdge.Trailing
    insets.left > insets.right -> NavEdge.Leading
    else -> NavEdge.Trailing
}
