package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCaptionTest {
    @Test fun listMeta_joinsAllParts() {
        assertEquals(
            "12:34 · 12 clips · 1m 04s · 84 MB",
            SessionCaption.listMeta(time = "12:34", clipCountLabel = "12 clips", durationLabel = "1m 04s", sizeLabel = "84 MB"),
        )
    }
    @Test fun listMeta_dropsBlankClipCount_forLegacyRow() {
        assertEquals(
            "12:34 · 0m 22s · 19 MB",
            SessionCaption.listMeta(time = "12:34", clipCountLabel = "", durationLabel = "0m 22s", sizeLabel = "19 MB"),
        )
    }
    @Test fun gridCaption_isTimeOnly() {
        assertEquals("10:02", SessionCaption.gridCaption(time = "10:02"))
    }
}
