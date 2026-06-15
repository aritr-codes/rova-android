package com.aritr.rova.ui.library

/**
 * Pure formatter for the hero meta line — the session's WHAT: `12 clips · 1m · 209 MB`. The WHEN
 * (day · time) is the title ([SmartTitle]); kept out of here so title and meta don't repeat (owner
 * polish, 2026-06-15). All parts arrive already localized; blanks drop so legacy rows without a clip
 * count or duration collapse cleanly. Separator is the middot the rest of the app uses.
 * Framework-free -> JVM-tested (house seam pattern).
 */
object HeroMetaFormatter {
    private const val SEP = " · "
    fun format(clipCountLabel: String, durationLabel: String, sizeLabel: String): String =
        listOf(clipCountLabel, durationLabel, sizeLabel)
            .filter { it.isNotBlank() }
            .joinToString(SEP)
}
