package com.aritr.rova.ui.library

/**
 * Pure port of the frozen bento row planner (docs/design/library-bento.html
 * v3.2.1, `TIERS` + `buildRows`). Values are FROZEN — do not re-derive.
 * Rotation keys on day AGE (stable across filter/search), every DualShot
 * session is guaranteed a span >= 3 slot, a single leftover takes the fill
 * row, and only a featured day with >= 3 sessions leads with the hero.
 * Day age is computed by the caller (LibraryDateLabels — DST-safe).
 */
object BentoRowPlanner {

    data class RowPattern(val spans: List<Int>, val heightDp: Int)

    data class Tier(val hero: RowPattern?, val rows: List<RowPattern>, val fill1: RowPattern)

    private val FEATURED = Tier(
        hero = RowPattern(listOf(6), 208),
        rows = listOf(
            RowPattern(listOf(3, 3), 152),
            RowPattern(listOf(4, 2), 164),
            RowPattern(listOf(2, 4), 164),
        ),
        fill1 = RowPattern(listOf(6), 192),
    )
    private val STANDARD = Tier(
        hero = null,
        rows = listOf(
            RowPattern(listOf(4, 2), 148),
            RowPattern(listOf(3, 3), 128),
            RowPattern(listOf(2, 2, 2), 104),
            RowPattern(listOf(2, 4), 148),
        ),
        fill1 = RowPattern(listOf(6), 148),
    )
    private val ARCHIVE = Tier(
        hero = null,
        rows = listOf(
            RowPattern(listOf(2, 2, 2), 92),
            RowPattern(listOf(3, 3), 92),
        ),
        fill1 = RowPattern(listOf(6), 108),
    )

    fun tierFor(dayAge: Int): Tier = when {
        dayAge <= 1 -> FEATURED
        dayAge <= 6 -> STANDARD
        else -> ARCHIVE
    }

    fun plan(isDual: List<Boolean>, dayAge: Int): List<RowPattern> {
        val tier = tierFor(dayAge)
        val out = ArrayList<RowPattern>()
        var i = 0
        var rot = dayAge
        while (i < isDual.size) {
            val left = isDual.size - i
            if (left == 1) { out.add(tier.fill1); i++; continue }
            if (tier.hero != null && i == 0 && isDual.size >= 3) { out.add(tier.hero); i++; continue }
            val cands = tier.rows.filter { it.spans.size <= left }
            val off = rot % cands.size
            val ordered = cands.subList(off, cands.size) + cands.subList(0, off)
            val pick = ordered.firstOrNull { r ->
                r.spans.withIndex().all { (j, sp) -> sp >= 3 || isDual.getOrNull(i + j) != true }
            } ?: ordered.first()
            out.add(pick)
            i += pick.spans.size
            rot++
        }
        return out
    }
}
