package com.aritr.rova.ui.screens.player

import com.aritr.rova.ui.theme.RovaIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * PR-5 Player slice (ADR-0031): the transport toggle resolves to the board-exact play/pause marks,
 * mirroring [com.aritr.rova.ui.library.components.LibraryIconSpecTest].
 */
class PlayerIconSpecTest {

    @Test
    fun playing_resolvesToPause() {
        assertEquals(RovaIcons.Pause.outline, PlayerIconSpec.transportGlyph(isPlaying = true))
    }

    @Test
    fun paused_resolvesToPlay() {
        assertEquals(RovaIcons.Play.glyph, PlayerIconSpec.transportGlyph(isPlaying = false))
    }

    @Test
    fun playAndPauseAreDistinctMarks() {
        assertNotEquals(
            PlayerIconSpec.transportGlyph(isPlaying = true),
            PlayerIconSpec.transportGlyph(isPlaying = false),
        )
    }
}
