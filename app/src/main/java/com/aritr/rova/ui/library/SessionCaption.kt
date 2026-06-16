package com.aritr.rova.ui.library

/**
 * Pure caption builders so a card/row reads as a recording SESSION, not a random thumbnail.
 * List rows show the WHAT meta (clips · duration · size); the WHEN (day · time) is the row title
 * ([SmartTitle]) so the two don't repeat (owner polish, 2026-06-15). Grid tiles stay terse (the
 * clip-count chip, duration badge, and status badge are separate overlays). Blank parts drop so legacy
 * single-file rows (clipCount 0) collapse cleanly. Framework-free -> JVM-tested.
 */
object SessionCaption {
    private const val SEP = " · "
    fun listMeta(clipCountLabel: String, durationLabel: String, sizeLabel: String): String =
        listOf(clipCountLabel, durationLabel, sizeLabel).filter { it.isNotBlank() }.joinToString(SEP)
    fun gridCaption(time: String): String = time
}
