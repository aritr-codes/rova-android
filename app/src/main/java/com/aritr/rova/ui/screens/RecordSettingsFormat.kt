package com.aritr.rova.ui.screens

// ── Settings-card / settings-sheet display-value formatters (sentinel-blind).
// Moved here from SessionSettingsSheet.kt (which is being retired) so the
// Phase-2 RecordChrome settings card and the new SettingsSheet share one
// source. Top-level `internal`, same package — call sites need no import.

internal fun recordClipValue(seconds: Int): String = when {
    seconds <= 0 -> "0 s"
    seconds < 60 -> "$seconds s"
    seconds == 60 -> "1 m"
    seconds % 60 == 0 -> "${seconds / 60} m"
    else -> "$seconds s"
}

internal fun recordRepeatsValue(loopCount: Int): String =
    if (loopCount < 0) "Until you stop" else loopCount.toString()

internal fun recordWaitValue(intervalMinutes: Int): String = when {
    intervalMinutes <= 0 -> "None"
    intervalMinutes == 60 -> "1 h"
    intervalMinutes % 60 == 0 -> "${intervalMinutes / 60} h"
    else -> "$intervalMinutes m"
}

/**
 * Compact repeats value for the inline stepper's narrow value slot — the
 * continuous sentinel renders as `∞` (the full "Until you stop" string from
 * [recordRepeatsValue] is too wide for the 34 dp slot; the Phase-2 settings
 * card still uses [recordRepeatsValue]).
 */
internal fun recordRepeatsStepperValue(loopCount: Int): String =
    if (loopCount < 0) "∞" else loopCount.toString()

/**
 * Compact, glanceable summary for a preset TILE — `clip · repeats · quality`,
 * e.g. "30 s · ×20 · FHD" or "1 m · ∞ · HD". Wait is intentionally omitted
 * (usually "None") to keep the tile to one tidy line (preset-ui-polish spec
 * §2.1). Repeats render as "∞" for continuous (loopCount < 0) else "×N".
 * Reuses [recordClipValue] so the clip vocabulary matches the strip exactly.
 */
internal fun presetTileSummary(durationSeconds: Int, loopCount: Int, resolution: String): String {
    val repeats = if (loopCount < 0) "∞" else "×$loopCount"
    return "${recordClipValue(durationSeconds)} · $repeats · $resolution"
}
