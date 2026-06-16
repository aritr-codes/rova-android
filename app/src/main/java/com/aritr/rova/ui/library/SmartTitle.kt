package com.aritr.rova.ui.library

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ADR-0030 — derives the default Library title for a session that has no
 * user [LibraryMetadataEntry.customTitle]. Pure: takes manifest-derived
 * primitives, returns a string. Format: `EEE · h:mm a · N clip(s) · <dur>`.
 */
object SmartTitle {

    fun derive(
        startedAtMillis: Long,
        segmentCount: Int,
        totalDurationMs: Long,
        locale: Locale,
        tz: TimeZone,
    ): String {
        val day = SimpleDateFormat("EEE", locale).apply { timeZone = tz }.format(Date(startedAtMillis))
        val time = SimpleDateFormat("h:mm a", locale).apply { timeZone = tz }.format(Date(startedAtMillis))
        val clips = if (segmentCount == 1) "1 clip" else "$segmentCount clips"
        return "$day · $time · $clips · ${formatDuration(totalDurationMs)}"
    }

    /** Public m/s duration label (`12m`, `1m 30s`, `42s`) — shared by cards + semantics. */
    fun durationLabel(ms: Long): String = formatDuration(ms)

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return when {
            m == 0L -> "${s}s"
            s == 0L -> "${m}m"
            else -> "${m}m ${s}s"
        }
    }
}
