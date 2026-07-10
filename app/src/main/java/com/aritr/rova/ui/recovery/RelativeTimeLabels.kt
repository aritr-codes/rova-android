package com.aritr.rova.ui.recovery

/**
 * Which rung of the APPX-G recency ladder an instant lands on.
 *
 * A KIND, never user copy — `"just now"` / `"yesterday"` / `"N minutes ago"` are
 * resource-backed and resolved in the composable layer (ADR-0022, `checkNoHardcodedUiStrings`).
 * The technique is [com.aritr.rova.ui.library.LibraryDateLabels]' `DayHeaderKind`, harvested
 * by method rather than by code: this ladder measures *elapsed duration*, not calendar days,
 * so it has no `startOfDay`/`dayDiff` arithmetic to share.
 */
enum class RelativeTimeKind {
    /** `< 60s` */
    JUST_NOW,

    /** `< 60m` — [RelativeTimeLabel.count] is the whole minutes elapsed, `1..59`. */
    MINUTES,

    /** `< 24h` — [RelativeTimeLabel.count] is the whole hours elapsed, `1..23`. */
    HOURS,

    /** `< 48h` */
    YESTERDAY,

    /** `< 7d` — [RelativeTimeLabel.count] is the whole days elapsed, `2..6`. */
    DAYS,

    /** `>= 7d` — render the absolute date from [RelativeTimeLabel.atMillis]. */
    DATE,
}

/**
 * A recency label, minted once at mapping time and immutable thereafter.
 *
 * @param count populated only for the three counted rungs ([RelativeTimeKind.MINUTES],
 *   [RelativeTimeKind.HOURS], [RelativeTimeKind.DAYS]); `null` otherwise.
 * @param atMillis the instant described. Carried so the a11y label, the "why" expander, and
 *   diagnostics can render the *absolute* time — which APPX-G confines to exactly those three
 *   places and forbids in the primary UI.
 */
data class RelativeTimeLabel(
    val kind: RelativeTimeKind,
    val count: Int?,
    val atMillis: Long,
)

/**
 * The APPX-G recency ladder (`docs/design/warnings-recovery.html:1088`), verbatim:
 *
 * `<60s` → "just now" · `<60m` → N minutes ago · `<24h` → N hours ago ·
 * `<48h` → yesterday · `<7d` → N days ago · else the absolute date.
 *
 * ## Why this is DST-safe without any calendar arithmetic
 *
 * Every rung is an **elapsed duration** between two instants, so the result cannot depend on
 * the device's zone, its UTC offset, or whether a DST transition falls between the two. The
 * frozen spec's phrase "DST-safe, generalized from `LibraryDateLabels`" names the *property*;
 * the property is here obtained by construction rather than by `LibraryDateLabels`' rounding
 * trick, which exists only because *calendar-day* differences must survive 23h/25h days.
 * Copying `startOfDay`/`dayDiff` here would add a zone dependency the ladder does not have.
 *
 * ## Invariants
 *
 * - **No ambient clock.** `nowMillis` is a parameter. The caller injects it through
 *   [RecoveryClock], which is what makes the mapper deterministic under test.
 * - **A future instant clamps to [RelativeTimeKind.JUST_NOW]**, never a negative count —
 *   mirroring `LibraryDateLabels.dayAge`'s `coerceAtLeast(0)` ("a future day is today").
 *   Clock skew and a manifest written a few ms ahead of the read must not print "-1 minutes ago".
 * - **Counts floor**, they never round: 119 minutes is "1 hour ago", not "2 hours ago".
 */
object RelativeTimeLabels {

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60L * MINUTE_MS
    private const val DAY_MS = 24L * HOUR_MS
    private const val WEEK_MS = 7L * DAY_MS

    fun label(atMillis: Long, nowMillis: Long): RelativeTimeLabel {
        val elapsed = (nowMillis - atMillis).coerceAtLeast(0L)
        return when {
            elapsed < MINUTE_MS -> label(RelativeTimeKind.JUST_NOW, null, atMillis)
            elapsed < HOUR_MS -> label(RelativeTimeKind.MINUTES, elapsed / MINUTE_MS, atMillis)
            elapsed < DAY_MS -> label(RelativeTimeKind.HOURS, elapsed / HOUR_MS, atMillis)
            elapsed < 2 * DAY_MS -> label(RelativeTimeKind.YESTERDAY, null, atMillis)
            elapsed < WEEK_MS -> label(RelativeTimeKind.DAYS, elapsed / DAY_MS, atMillis)
            else -> label(RelativeTimeKind.DATE, null, atMillis)
        }
    }

    private fun label(kind: RelativeTimeKind, count: Long?, atMillis: Long) =
        RelativeTimeLabel(kind, count?.toInt(), atMillis)
}
