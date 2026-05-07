package com.aritr.rova.ui.screens

import com.aritr.rova.data.SessionConfig

/**
 * Phase 2.2 — pure formatters for [LibrarySessionConfigDialog] rows.
 *
 * The dialog is a read-only surface over [SessionConfig] persisted in
 * a finalized session's manifest. These helpers translate the raw
 * stored values into the user-facing vocabulary documented in
 * `docs/UI_NAV_GRAPH.md` §4.3 + `mockups/new_uiux/03-history-library.html`
 * (the "View Settings" popup):
 *
 * - **Clip length** is the wall-clock seconds the recorder captured
 *   per loop (`SessionConfig.durationSeconds`). Multiples of 60 are
 *   rendered in minutes; mixed values keep a `m s` form.
 * - **Repeats** is [SessionConfig.loopCount]. The continuous sentinel
 *   `-1` (and any other negative value, defensively) renders as
 *   "Until you stop" so the dialog never surfaces a raw `-1`.
 * - **Wait** is [SessionConfig.intervalMinutes]. `0` renders as "None"
 *   per the chip vocabulary; multiples of 60 are rendered in hours.
 * - **Quality** is [SessionConfig.resolution] — already a picker
 *   label (`"SD" / "HD" / "FHD" / "4K"`) per the `SessionConfig`
 *   KDoc; only trim + blank-fallback is applied.
 *
 * Internal visibility so the JVM unit tests in the same module can
 * import these without exposing them to other production callers.
 * Phase 6 (orientation) may add a `formatMode` once
 * `SessionConfig.orientationMode` lands; the dialog deliberately
 * omits a Mode row in v1.0 because no field exists yet.
 */
internal object LibrarySessionConfigFormatters {

    fun formatClipLength(durationSeconds: Int): String {
        val s = durationSeconds.coerceAtLeast(0)
        if (s < 60) return "$s s"
        val minutes = s / 60
        val seconds = s % 60
        return if (seconds == 0) "$minutes min" else "$minutes min $seconds s"
    }

    fun formatRepeats(loopCount: Int): String =
        if (loopCount < 0) "Until you stop" else loopCount.toString()

    fun formatWait(intervalMinutes: Int): String {
        val m = intervalMinutes.coerceAtLeast(0)
        if (m == 0) return "None"
        if (m < 60) return "$m min"
        val hours = m / 60
        val mins = m % 60
        return if (mins == 0) "$hours h" else "$hours h $mins min"
    }

    fun formatQuality(resolution: String): String =
        resolution.trim().ifEmpty { "—" }
}
