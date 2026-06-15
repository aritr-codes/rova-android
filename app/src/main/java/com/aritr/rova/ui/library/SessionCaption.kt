package com.aritr.rova.ui.library

/**
 * Pure caption builders so a card/row reads as a recording SESSION, not a random thumbnail.
 * List rows show the dense meta (time · clips · duration · size); grid tiles stay terse (time only —
 * the clip-count chip, duration badge, and status badge are separate overlays). Blank parts drop so
 * legacy single-file rows (clipCount 0) collapse cleanly. Framework-free -> JVM-tested.
 */
object SessionCaption {
    private const val SEP = " · "
    fun listMeta(time: String, clipCountLabel: String, durationLabel: String, sizeLabel: String): String =
        listOf(time, clipCountLabel, durationLabel, sizeLabel).filter { it.isNotBlank() }.joinToString(SEP)
    fun gridCaption(time: String): String = time
}
