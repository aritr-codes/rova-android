package com.aritr.rova.ui.library

import com.aritr.rova.data.StopReason
import com.aritr.rova.ui.library.components.LibraryIconSpec
import com.aritr.rova.ui.theme.IconStatus
import com.aritr.rova.ui.theme.RovaGlyphs
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryIconSpecTest {
    @Test fun `auto-stopped thermal uses thermal glyph with interrupted lock`() {
        val icon = LibraryIconSpec.badgeGlyph(LibraryBadge.AUTO_STOPPED, StopReason.THERMAL)
        assertEquals(RovaGlyphs.Thermal.outline, icon.glyph)
        assertEquals(IconStatus.Interrupted, icon.status)
    }

    @Test fun `auto-stopped low storage uses storage glyph`() {
        val icon = LibraryIconSpec.badgeGlyph(LibraryBadge.AUTO_STOPPED, StopReason.LOW_STORAGE)
        assertEquals(RovaGlyphs.Storage.outline, icon.glyph)
        assertEquals(IconStatus.Interrupted, icon.status)
    }

    @Test fun `auto-stopped unknown reason falls back to thermal`() {
        val icon = LibraryIconSpec.badgeGlyph(LibraryBadge.AUTO_STOPPED, null)
        assertEquals(RovaGlyphs.Thermal.outline, icon.glyph)
    }

    @Test fun `recovered and interrupted ignore reason`() {
        assertEquals(IconStatus.Recovered, LibraryIconSpec.badgeGlyph(LibraryBadge.RECOVERED, null).status)
        assertEquals(IconStatus.Interrupted, LibraryIconSpec.badgeGlyph(LibraryBadge.INTERRUPTED, null).status)
    }
}
