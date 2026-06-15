package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class HeroMetaFormatterTest {
    @Test fun joinsAllParts_withMiddot() {
        val out = HeroMetaFormatter.format(
            clipCountLabel = "12 clips", durationLabel = "1m", sizeLabel = "209 MB",
        )
        assertEquals("12 clips · 1m · 209 MB", out)
    }

    @Test fun dropsBlankParts() {
        val out = HeroMetaFormatter.format(
            clipCountLabel = "", durationLabel = "1m", sizeLabel = "209 MB",
        )
        assertEquals("1m · 209 MB", out)
    }

    @Test fun singlePart_noSeparator() {
        assertEquals("209 MB", HeroMetaFormatter.format("", "", "209 MB"))
    }
}
