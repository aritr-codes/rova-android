package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import org.junit.Assert.assertEquals
import org.junit.Test

class TileSemanticsTest {

    private fun row(
        title: String = "Sun · 2:32 PM · 8 clips · 12m",
        dur: Long = 12 * 60_000L,
        topology: CaptureTopology = CaptureTopology.Single,
        badge: LibraryBadge? = null,
    ) = LibraryRow("k", title, "", 0, dur, 0, 1, topology, badge, false)

    private val frag = TileSemantics.Fragments(
        durationWord = "duration", recoveredWord = "Recovered", interruptedWord = "Interrupted",
        dualWord = "Portrait plus Landscape",
    )

    @Test fun `title plus spoken duration`() {
        assertEquals(
            "Sun · 2:32 PM · 8 clips · 12m, duration 12m",
            TileSemantics.describe(row(), frag),
        )
    }

    @Test fun `appends recovered status`() {
        assertEquals(
            "Sun · 2:32 PM · 8 clips · 12m, duration 12m, Recovered",
            TileSemantics.describe(row(badge = LibraryBadge.RECOVERED), frag),
        )
    }

    @Test fun `appends interrupted and P+L`() {
        assertEquals(
            "Sun · 2:32 PM · 8 clips · 12m, duration 12m, Interrupted, Portrait plus Landscape",
            TileSemantics.describe(row(badge = LibraryBadge.INTERRUPTED, topology = CaptureTopology.DualShot), frag),
        )
    }

    @Test fun `under a minute speaks seconds`() {
        assertEquals(
            "T, duration 42s",
            TileSemantics.describe(row(title = "T", dur = 42_000L), frag),
        )
    }

    @Test fun `bento single label leads with orientation and verb play`() {
        val label = TileSemantics.bentoLabel(
            selecting = false, orientationWord = "portrait recording", dayAndTime = "Today 11:42 am",
            duration = "2m", favorite = true, latest = true,
        )
        assertEquals("Play portrait recording, Today 11:42 am, 2m, favorite, latest recording", label)
    }

    @Test fun `bento selection mode swaps the verb`() {
        val label = TileSemantics.bentoLabel(
            selecting = true, orientationWord = null, dayAndTime = "Today 11:42 am",
            duration = "2m", favorite = false, latest = false,
        )
        assertEquals("Select Today 11:42 am, 2m", label)
    }

    @Test fun `bento pane label names the side`() {
        val label = TileSemantics.bentoPaneLabel(selecting = false, side = "Portrait", dayAndTime = "Today 11:42 am", duration = "2m")
        assertEquals("Play Portrait side, Today 11:42 am, 2m", label)
    }

    // ── v3.3 hairline spoken fraction (spec pgSpeak) ──────────────────────

    @Test fun `bento labels speak the watched fraction when playing`() {
        val single = TileSemantics.bentoLabel(
            selecting = false, orientationWord = null, dayAndTime = "Today 11:42 am",
            duration = "2m", favorite = false, latest = false, progressPercent = 37,
        )
        assertEquals("Play Today 11:42 am, 2m, partially watched 37%", single)

        val pane = TileSemantics.bentoPaneLabel(
            selecting = false, side = "Landscape", dayAndTime = "Today 11:42 am", duration = "2m", progressPercent = 8,
        )
        assertEquals("Play Landscape side, Today 11:42 am, 2m, partially watched 8%", pane)
    }

    @Test fun `selection mode never speaks the fraction (bar is hidden)`() {
        val label = TileSemantics.bentoLabel(
            selecting = true, orientationWord = null, dayAndTime = "Today 11:42 am",
            duration = "2m", favorite = false, latest = false, progressPercent = 37,
        )
        assertEquals("Select Today 11:42 am, 2m", label)
    }
}
