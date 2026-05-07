package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 2.2 — pure JVM tests for [LibrarySessionConfigFormatters].
 *
 * The popup is read-only and pulls config from a session manifest, so
 * formatter correctness is the only meaningful surface to test here.
 * Robolectric / instrumentation coverage of the dialog itself is
 * deferred to Phase 2.2 manual smoke (see `NEW_UI_BACKEND_REPLAN.md`
 * §5 Phase 2 row 2.2).
 */
class LibrarySessionConfigFormattersTest {

    // ── formatClipLength ──────────────────────────────────────────

    @Test
    fun `clip length below a minute renders in seconds`() {
        assertEquals("5 s", LibrarySessionConfigFormatters.formatClipLength(5))
        assertEquals("30 s", LibrarySessionConfigFormatters.formatClipLength(30))
        assertEquals("59 s", LibrarySessionConfigFormatters.formatClipLength(59))
    }

    @Test
    fun `clip length exactly one minute renders as 1 min`() {
        assertEquals("1 min", LibrarySessionConfigFormatters.formatClipLength(60))
    }

    @Test
    fun `clip length multiple of 60 renders in minutes only`() {
        assertEquals("2 min", LibrarySessionConfigFormatters.formatClipLength(120))
        assertEquals("5 min", LibrarySessionConfigFormatters.formatClipLength(300))
    }

    @Test
    fun `clip length non-multiple keeps min and seconds form`() {
        assertEquals("1 min 30 s", LibrarySessionConfigFormatters.formatClipLength(90))
        assertEquals("2 min 15 s", LibrarySessionConfigFormatters.formatClipLength(135))
    }

    @Test
    fun `clip length zero renders as 0 s`() {
        assertEquals("0 s", LibrarySessionConfigFormatters.formatClipLength(0))
    }

    @Test
    fun `clip length negative is clamped to 0 s`() {
        assertEquals("0 s", LibrarySessionConfigFormatters.formatClipLength(-5))
    }

    // ── formatRepeats ─────────────────────────────────────────────

    @Test
    fun `repeats positive renders the integer as-is`() {
        assertEquals("1", LibrarySessionConfigFormatters.formatRepeats(1))
        assertEquals("10", LibrarySessionConfigFormatters.formatRepeats(10))
        assertEquals("100", LibrarySessionConfigFormatters.formatRepeats(100))
    }

    @Test
    fun `repeats zero renders as 0`() {
        assertEquals("0", LibrarySessionConfigFormatters.formatRepeats(0))
    }

    @Test
    fun `repeats continuous sentinel -1 renders as Until you stop`() {
        assertEquals(
            "Until you stop",
            LibrarySessionConfigFormatters.formatRepeats(-1)
        )
    }

    @Test
    fun `repeats any negative defensively renders as Until you stop`() {
        // Phase 2.2 — defensive: only -1 is the documented sentinel,
        // but other negatives must never leak through as raw "-7"
        // either. The popup never surfaces a raw negative.
        assertEquals(
            "Until you stop",
            LibrarySessionConfigFormatters.formatRepeats(-7)
        )
    }

    // ── formatWait ────────────────────────────────────────────────

    @Test
    fun `wait zero renders as None`() {
        assertEquals("None", LibrarySessionConfigFormatters.formatWait(0))
    }

    @Test
    fun `wait below an hour renders in minutes`() {
        assertEquals("1 min", LibrarySessionConfigFormatters.formatWait(1))
        assertEquals("5 min", LibrarySessionConfigFormatters.formatWait(5))
        assertEquals("30 min", LibrarySessionConfigFormatters.formatWait(30))
        assertEquals("59 min", LibrarySessionConfigFormatters.formatWait(59))
    }

    @Test
    fun `wait exactly 60 minutes renders as 1 h`() {
        assertEquals("1 h", LibrarySessionConfigFormatters.formatWait(60))
    }

    @Test
    fun `wait multiple of 60 renders in hours only`() {
        assertEquals("2 h", LibrarySessionConfigFormatters.formatWait(120))
        assertEquals("3 h", LibrarySessionConfigFormatters.formatWait(180))
    }

    @Test
    fun `wait non-multiple keeps hour and minutes form`() {
        assertEquals("1 h 30 min", LibrarySessionConfigFormatters.formatWait(90))
        assertEquals("2 h 15 min", LibrarySessionConfigFormatters.formatWait(135))
    }

    @Test
    fun `wait negative is clamped to None`() {
        assertEquals("None", LibrarySessionConfigFormatters.formatWait(-3))
    }

    // ── formatQuality ─────────────────────────────────────────────

    @Test
    fun `quality picker label passes through unchanged`() {
        assertEquals("FHD", LibrarySessionConfigFormatters.formatQuality("FHD"))
        assertEquals("HD", LibrarySessionConfigFormatters.formatQuality("HD"))
        assertEquals("SD", LibrarySessionConfigFormatters.formatQuality("SD"))
        assertEquals("4K", LibrarySessionConfigFormatters.formatQuality("4K"))
    }

    @Test
    fun `quality whitespace is trimmed`() {
        assertEquals("FHD", LibrarySessionConfigFormatters.formatQuality("  FHD  "))
    }

    @Test
    fun `quality blank input falls back to em dash`() {
        assertEquals("—", LibrarySessionConfigFormatters.formatQuality(""))
        assertEquals("—", LibrarySessionConfigFormatters.formatQuality("   "))
    }
}
