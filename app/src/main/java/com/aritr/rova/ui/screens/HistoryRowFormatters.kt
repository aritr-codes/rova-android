package com.aritr.rova.ui.screens

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Slice 4 — pure formatters for the Library list. Plain JVM, no
 * Android, no Compose. Tested directly.
 *
 * Naming convention: every public function takes a `now: Long` (or
 * `nowMillis`) and a `locale: Locale` so calls are deterministic and
 * unit-testable across timezones / locales. Callers in production
 * pass `System.currentTimeMillis()` and `Locale.getDefault()`.
 */
object HistoryRowFormatters {

    /**
     * Primary row title — the human date · time string the row's
     * primary `Text` shows, e.g. `"May 4 · 2:22 PM"`. Year is
     * intentionally omitted; the date-group sticky header carries
     * the year for prior-year recordings.
     *
     * Locale shapes the month-name / AM-PM rendering.
     */
    fun formatPrimaryDateTime(
        millis: Long,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val month = SimpleDateFormat("MMM", locale).apply { this.timeZone = timeZone }
        val day = SimpleDateFormat("d", locale).apply { this.timeZone = timeZone }
        val time = SimpleDateFormat("h:mm a", locale).apply { this.timeZone = timeZone }
        val date = Date(millis)
        return "${month.format(date)} ${day.format(date)} · ${time.format(date)}"
    }

    /**
     * 24-hour time component used as a secondary meta line — `"14:22"`.
     * Stable two-digit width.
     */
    fun formatTime24(
        millis: Long,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val fmt = SimpleDateFormat("HH:mm", locale).apply { this.timeZone = timeZone }
        return fmt.format(Date(millis))
    }

    /**
     * Sticky-header label: `"Today"`, `"Yesterday"`, or
     * `"May 1, 2026"` for older dates. Year is included only on the
     * fallback branch so adjacent same-year groups read cleanly.
     */
    fun formatGroupHeader(
        millis: Long,
        nowMillis: Long,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val now = Calendar.getInstance(timeZone, locale).apply { timeInMillis = nowMillis }
        val that = Calendar.getInstance(timeZone, locale).apply { timeInMillis = millis }
        val sameYearAndDay: (Calendar, Calendar) -> Boolean = { a, b ->
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        }
        if (sameYearAndDay(now, that)) return "Today"
        val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (sameYearAndDay(yesterday, that)) return "Yesterday"
        val long = SimpleDateFormat("MMMM d, yyyy", locale).apply { this.timeZone = timeZone }
        return long.format(Date(millis))
    }

    /**
     * File-size label — `"82.4 MB"` or `"812 KB"`. Same shape the
     * existing History screen uses, lifted into a tested function so
     * the row a11y label can produce identical text without a fragile
     * private duplicate.
     */
    fun formatSize(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        val kb = safe / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }

    /**
     * TalkBack content description for a single library row. Leads
     * with the human date/time so a screen-reader user hears the
     * recording in the same shape it is visually scanned. Quality
     * and size follow because they're the next-most-useful filters
     * when picking among recent recordings.
     */
    fun formatRowAccessibility(
        primaryDateTime: String,
        sizeBytes: Long,
        quality: String,
        durationLabel: String? = null
    ): String {
        val sizeText = formatSize(sizeBytes)
        return buildString {
            append("Recording ")
            append(primaryDateTime)
            append(", quality ")
            append(quality)
            if (!durationLabel.isNullOrBlank()) {
                append(", duration ")
                append(durationLabel)
            }
            append(", size ")
            append(sizeText)
        }
    }

    /**
     * "More actions for X" content description for the per-row
     * overflow icon button. Mirrors the mockup's a11y label.
     */
    fun formatMoreActionsLabel(primaryDateTime: String): String =
        "More actions for $primaryDateTime recording"

    /**
     * Retention pill copy. `null` means the user has auto-delete
     * disabled, so the pill is hidden.
     */
    fun formatRetentionPill(autoDeleteEnabled: Boolean, keepLatest: Int): String? {
        if (!autoDeleteEnabled) return null
        if (keepLatest <= 0) return null
        return "Auto-keep latest $keepLatest"
    }

    /**
     * Compact summary line for the top bar — `"7 recordings · 412 MB"`.
     */
    fun formatLibrarySummary(recordingCount: Int, totalBytes: Long): String {
        val countWord = if (recordingCount == 1) "recording" else "recordings"
        return "$recordingCount $countWord · ${formatSize(totalBytes)}"
    }
}
