package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class HeroMetaFormatterTest {
    @Test fun joinsAllParts_withMiddot() {
        val out = HeroMetaFormatter.format(
            dayLabel = "Mon", timeLabel = "12:34", clipCountLabel = "12 clips", durationLabel = "1m",
        )
        assertEquals("Mon · 12:34 · 12 clips · 1m", out)
    }

    @Test fun dropsBlankParts() {
        val out = HeroMetaFormatter.format(
            dayLabel = "Mon", timeLabel = "12:34", clipCountLabel = "", durationLabel = "1m",
        )
        assertEquals("Mon · 12:34 · 1m", out)
    }

    @Test fun singlePart_noSeparator() {
        assertEquals("Mon", HeroMetaFormatter.format("Mon", "", "", ""))
    }
}
