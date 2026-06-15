package com.aritr.rova.ui.library

/**
 * Pure formatter for the hero meta line ("Mon · 12:34 · 12 clips · 1m"). All parts arrive already
 * localized (caller supplies stringResource / SmartTitle output); blanks are dropped so legacy rows
 * without a clip count or duration collapse cleanly. Separator is the middot the rest of the app uses.
 * Framework-free -> JVM-tested (house seam pattern).
 */
object HeroMetaFormatter {
    private const val SEP = " · "
    fun format(dayLabel: String, timeLabel: String, clipCountLabel: String, durationLabel: String): String =
        listOf(dayLabel, timeLabel, clipCountLabel, durationLabel)
            .filter { it.isNotBlank() }
            .joinToString(SEP)
}
