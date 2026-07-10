package com.aritr.rova.ui.recovery

import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * APPX-G recency ladder (`docs/design/warnings-recovery.html:1088-1090`), verbatim:
 *
 * `<60s` → "just now" · `<60m` → N minutes ago · `<24h` → N hours ago ·
 * `<48h` → yesterday · `<7d` → N days ago · else the absolute date.
 *
 * The rungs are **elapsed durations**, not calendar-day comparisons, so the ladder is
 * timezone- and DST-invariant by construction. These tests pin that as a property rather
 * than assuming it.
 */
class RelativeTimeLabelsTest {

    private companion object {
        const val SECOND = 1_000L
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L

        /** 2026-07-09T16:12:00Z — the instant the frozen spec's specimen speaks ("4:12 PM on July 9"). */
        const val NOW = 1_783_613_520_000L
    }

    private val originalZone: TimeZone = TimeZone.getDefault()

    @After fun restoreZone() = TimeZone.setDefault(originalZone)

    private fun labelAgo(elapsed: Long) = RelativeTimeLabels.label(atMillis = NOW - elapsed, nowMillis = NOW)

    // ── "just now" ───────────────────────────────────────────────────────

    @Test fun `zero elapsed is just now`() {
        assertEquals(RelativeTimeKind.JUST_NOW, labelAgo(0).kind)
    }

    @Test fun `just under a minute is just now`() {
        assertEquals(RelativeTimeKind.JUST_NOW, labelAgo(59 * SECOND).kind)
        assertEquals(RelativeTimeKind.JUST_NOW, labelAgo(MINUTE - 1).kind)
    }

    /** Clock skew / a future timestamp clamps to "just now" — `LibraryDateLabels.dayAge`'s coerceAtLeast(0). */
    @Test fun `future timestamp clamps to just now`() {
        assertEquals(RelativeTimeKind.JUST_NOW, labelAgo(-5 * HOUR).kind)
        assertEquals(RelativeTimeKind.JUST_NOW, labelAgo(-DAY * 400).kind)
    }

    // ── minute boundary: 59s / 60s ───────────────────────────────────────

    @Test fun `exactly one minute becomes minutes`() {
        val label = labelAgo(MINUTE)
        assertEquals(RelativeTimeKind.MINUTES, label.kind)
        assertEquals(1, label.count)
    }

    @Test fun `minutes floor toward zero`() {
        assertEquals(1, labelAgo(MINUTE + 59 * SECOND).count)
        assertEquals(2, labelAgo(2 * MINUTE).count)
        assertEquals(59, labelAgo(59 * MINUTE).count)
        assertEquals(59, labelAgo(HOUR - 1).count)
    }

    // ── hour boundary: 59m / 60m ─────────────────────────────────────────

    @Test fun `exactly one hour becomes hours`() {
        val label = labelAgo(HOUR)
        assertEquals(RelativeTimeKind.HOURS, label.kind)
        assertEquals(1, label.count)
    }

    @Test fun `the spec specimen reads two hours ago`() {
        val label = labelAgo(2 * HOUR)
        assertEquals(RelativeTimeKind.HOURS, label.kind)
        assertEquals(2, label.count)
    }

    @Test fun `hours floor toward zero up to twenty three`() {
        assertEquals(23, labelAgo(23 * HOUR).count)
        assertEquals(23, labelAgo(DAY - 1).count)
    }

    // ── day boundary: 23:59:59.999 / 24:00 ───────────────────────────────

    @Test fun `exactly twenty four hours becomes yesterday`() {
        val label = labelAgo(DAY)
        assertEquals(RelativeTimeKind.YESTERDAY, label.kind)
        assertNull(label.count)
    }

    // ── yesterday boundary: 47:59 / 48:00 ────────────────────────────────

    @Test fun `just under forty eight hours is still yesterday`() {
        assertEquals(RelativeTimeKind.YESTERDAY, labelAgo(2 * DAY - 1).kind)
        assertEquals(RelativeTimeKind.YESTERDAY, labelAgo(47 * HOUR + 59 * MINUTE).kind)
    }

    @Test fun `exactly forty eight hours becomes two days ago`() {
        val label = labelAgo(2 * DAY)
        assertEquals(RelativeTimeKind.DAYS, label.kind)
        assertEquals(2, label.count)
    }

    @Test fun `days floor toward zero up to six`() {
        assertEquals(2, labelAgo(2 * DAY + 23 * HOUR).count)
        assertEquals(6, labelAgo(6 * DAY).count)
        assertEquals(6, labelAgo(6 * DAY + 23 * HOUR).count)
    }

    // ── absolute-date boundary: 6d23h / 7d ───────────────────────────────

    @Test fun `just under seven days is still days ago`() {
        val label = labelAgo(7 * DAY - 1)
        assertEquals(RelativeTimeKind.DAYS, label.kind)
        assertEquals(6, label.count)
    }

    @Test fun `exactly seven days becomes the absolute date`() {
        val label = labelAgo(7 * DAY)
        assertEquals(RelativeTimeKind.DATE, label.kind)
        assertNull(label.count)
    }

    @Test fun `far past is the absolute date`() {
        assertEquals(RelativeTimeKind.DATE, labelAgo(365 * DAY).kind)
    }

    // ── the label carries its instant, so the a11y / why layers can format it ──

    @Test fun `label always carries the instant it describes`() {
        for (elapsed in listOf(0L, MINUTE, HOUR, DAY, 2 * DAY, 7 * DAY)) {
            assertEquals(NOW - elapsed, labelAgo(elapsed).atMillis)
        }
    }

    /** `count` is populated exactly for the three counted rungs, mirroring `DayHeaderLabel.weekday`. */
    @Test fun `count is non null exactly for minutes hours and days`() {
        assertNull(labelAgo(0).count)
        assertNull(labelAgo(DAY).count)
        assertNull(labelAgo(7 * DAY).count)
        assertEquals(5, labelAgo(5 * MINUTE).count)
        assertEquals(5, labelAgo(5 * HOUR).count)
        assertEquals(5, labelAgo(5 * DAY).count)
    }

    // ── timezone independence ────────────────────────────────────────────

    @Test fun `ladder is identical in every timezone`() {
        val zones = listOf(
            "UTC",
            "America/New_York",
            "Asia/Kolkata",      // +05:30 — half-hour offset
            "Pacific/Chatham",   // +12:45 — 45-minute offset
            "Pacific/Kiritimati", // +14:00 — the extreme
        )
        val elapsed = listOf(0L, 59 * SECOND, MINUTE, 59 * MINUTE, HOUR, 23 * HOUR, DAY, 2 * DAY - 1, 2 * DAY, 6 * DAY, 7 * DAY)

        val reference = elapsed.map { labelAgo(it) }
        for (zone in zones) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            assertEquals("ladder drifted in $zone", reference, elapsed.map { labelAgo(it) })
        }
    }

    // ── DST safety ───────────────────────────────────────────────────────
    //
    // M3 review round: these two are deliberately *vacuous today*. The ladder never touches
    // `TimeZone`/`Calendar`, so `setDefault` cannot change its output — they would pass with the
    // zone lines deleted. They are kept as a REGRESSION PIN: if anyone later reintroduces
    // calendar-day arithmetic here (e.g. by "harvesting" LibraryDateLabels' startOfDay/dayDiff),
    // the 24h/48h rungs start depending on local midnight and these instants turn RED.

    /**
     * Spring forward: 2026-03-08 07:00Z, America/New_York jumps 02:00 EST → 03:00 EDT.
     * The local wall clock advances 25 hours while 24 hours of real time elapse. An
     * elapsed-duration ladder must report "yesterday" regardless.
     */
    @Test fun `spring forward gap does not shift the ladder`() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val transition = 1_772_953_200_000L // 2026-03-08T07:00:00Z
        val before = transition - HOUR
        assertEquals(RelativeTimeKind.YESTERDAY, RelativeTimeLabels.label(before, before + DAY).kind)
        assertEquals(RelativeTimeKind.HOURS, RelativeTimeLabels.label(before, before + 23 * HOUR).kind)
        assertEquals(RelativeTimeKind.DAYS, RelativeTimeLabels.label(before, before + 2 * DAY).kind)
    }

    /**
     * Fall back: 2026-11-01 06:00Z, America/New_York repeats 01:00 (EDT → EST).
     * The local wall clock advances 23 hours while 24 hours of real time elapse.
     */
    @Test fun `fall back fold does not shift the ladder`() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val transition = 1_793_512_800_000L // 2026-11-01T06:00:00Z
        val before = transition - HOUR
        assertEquals(RelativeTimeKind.YESTERDAY, RelativeTimeLabels.label(before, before + DAY).kind)
        assertEquals(RelativeTimeKind.HOURS, RelativeTimeLabels.label(before, before + 23 * HOUR).kind)
        assertEquals(RelativeTimeKind.DAYS, RelativeTimeLabels.label(before, before + 2 * DAY).kind)
    }

    /** The ladder never reads an ambient clock: same inputs, same output, forever. */
    @Test fun `label is a pure function of its two inputs`() {
        repeat(3) {
            assertEquals(labelAgo(3 * HOUR), labelAgo(3 * HOUR))
        }
    }
}
