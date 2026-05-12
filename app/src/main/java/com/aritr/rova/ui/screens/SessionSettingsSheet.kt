package com.aritr.rova.ui.screens

// ── Settings-card / settings-sheet display-value formatters (sentinel-blind).
// Accessibility descriptions live in com.aritr.rova.ui.components.UiCopy; these
// are the short cell/row VALUES. Moved here from the old RecordIdleDock.kt.

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

/** v1.0.0 is Portrait-only; the Portrait/Landscape picker ships with Phase 6. */
internal fun recordModeValue(): String = "Portrait"
