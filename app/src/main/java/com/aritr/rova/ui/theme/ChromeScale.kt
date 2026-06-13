package com.aritr.rova.ui.theme

import kotlin.math.abs

/**
 * Screen-derived scale factor for record-chrome geometry (the config strip and
 * the compact floating settings panel). Anchored so the owner's reference
 * device (RZCYA1VBQ2H — 1080×2340 @ 420dpi → `smallestScreenWidthDp` ≈ 411)
 * lands at exactly **1.0**: at that width the panel keeps its tuned ~320dp side
 * and the strip keeps its tuned slot, while narrower phones shrink and wider
 * tablets / unfolded foldables grow — both proportionally, both clamped so the
 * chrome never collapses or balloons.
 *
 * Pure (no Compose / Android deps) so it is unit-testable under
 * `isReturnDefaultValues = true` (house pure-helper-extraction pattern). The
 * Compose accessor `rememberChromeScale()` (in `ui.screens`) feeds it
 * `LocalConfiguration.smallestScreenWidthDp`.
 *
 * Basis is `smallestScreenWidthDp` (the orientation-INVARIANT short side), so
 * the factor is identical in portrait and landscape — the strip/panel do NOT
 * resize when the device rotates, which would fight PR-ε's in-place spin
 * (ADR-0029 §B″).
 */
internal object ChromeScale {
    /** Reference-device short side (dp). [factor] == 1.0 here by construction. */
    const val BASELINE_SW_DP = 411f

    /** Floor — keeps chrome legible on the smallest supported phones (~320dp sw). */
    const val MIN_FACTOR = 0.88f

    /** Ceiling — stops the strip/panel ballooning on tablets & unfolded foldables. */
    const val MAX_FACTOR = 1.15f

    /**
     * Snap band (dp) around the baseline. The true short side is ~411.4dp, which
     * `Configuration.smallestScreenWidthDp` (an Int) may round to 410/411/412 across
     * builds/insets; snapping pins all of those to exactly 1.0 so the reference
     * device's strip/panel geometry stays byte-identical (codex review 2026-06-13).
     */
    const val SNAP_DP = 1f

    fun factor(smallestScreenWidthDp: Float): Float {
        if (abs(smallestScreenWidthDp - BASELINE_SW_DP) <= SNAP_DP) return 1f
        return (smallestScreenWidthDp / BASELINE_SW_DP).coerceIn(MIN_FACTOR, MAX_FACTOR)
    }
}
