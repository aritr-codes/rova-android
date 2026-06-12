package com.aritr.rova.ui.screens.chrome

/**
 * PR-ε (ADR-0029 §B″, spec §9) — which chrome model the record-home runs.
 * FixedPhysical: window locked portrait, contents counter-rotate (compact phones).
 * Adaptive: window rotates, β slot/axis-flip chrome (sw600dp+ — API 36/37 ignores
 * orientation-lock APIs there with NO camera exemption; iPad Camera makes the
 * same choice, so this is correct UX, not just compliance).
 */
internal enum class ChromeMode { FixedPhysical, Adaptive }

/** sw600dp is the platform's published lock-ignore threshold — single source. */
internal const val ADAPTIVE_MIN_SMALLEST_WIDTH_DP = 600

/** Maps [smallestScreenWidthDp] to [ChromeMode]; threshold is [ADAPTIVE_MIN_SMALLEST_WIDTH_DP]. */
internal fun chromeMode(smallestScreenWidthDp: Int): ChromeMode =
    if (smallestScreenWidthDp >= ADAPTIVE_MIN_SMALLEST_WIDTH_DP) ChromeMode.Adaptive
    else ChromeMode.FixedPhysical

/**
 * PR-ε (ADR-0029 §B″, spec §2.1) — the ONLY decision point for the record-route orientation
 * lock. Lock iff: on the record route AND no modal surface open AND FixedPhysical.
 * Opening a modal releases the lock so the window rotates normally and the
 * existing sheet/panel presentations apply (spec §7, owner-ratified "unlock
 * while open"). v1 wiring scopes modalOpen to the settings + thermal-tips
 * sheets; the warning sheet's visibility is not hoisted out of WarningCenter
 * yet and keeps the lock (recorded in ADR-0029 §B″ as a known deviation).
 */
internal object RecordChromeLockPolicy {
    fun shouldLock(isRecordRoute: Boolean, modalOpen: Boolean, chromeMode: ChromeMode): Boolean =
        isRecordRoute && !modalOpen && chromeMode == ChromeMode.FixedPhysical
}
