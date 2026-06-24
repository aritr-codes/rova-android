package com.aritr.rova.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * PR-7 — pins the playback-speed cycle/clamp/label contract for the
 * player speed chip. JVM-only, no Compose / ExoPlayer dependency.
 * Speed set is 0.5×/1×/1.5×/2× (4× deferred per spec §2.1 / owner Q2).
 */
class PlaybackSpeedPolicyTest {

    @Test fun `speed set is exactly the four review speeds`() {
        assertEquals(listOf(0.5f, 1f, 1.5f, 2f), PlaybackSpeedPolicy.SPEEDS)
        assertEquals(1f, PlaybackSpeedPolicy.DEFAULT)
    }

    @Test fun `next cycles 1 to 1_5 to 2 and wraps 2 to 0_5 to 1`() {
        assertEquals(1.5f, PlaybackSpeedPolicy.next(1f))
        assertEquals(2f, PlaybackSpeedPolicy.next(1.5f))
        assertEquals(0.5f, PlaybackSpeedPolicy.next(2f))   // wrap
        assertEquals(1f, PlaybackSpeedPolicy.next(0.5f))
    }

    @Test fun `next of off-list value snaps to nearest then advances`() {
        // 1.25 nearest supported is 1f (tie broken to lower index) -> advance 1.5
        assertEquals(1.5f, PlaybackSpeedPolicy.next(1.25f))
        // 3f nearest supported is 2f -> advance wraps to 0.5
        assertEquals(0.5f, PlaybackSpeedPolicy.next(3f))
    }

    @Test fun `clampToSupported coerces into range and snaps`() {
        assertEquals(2f, PlaybackSpeedPolicy.clampToSupported(4f))    // above max
        assertEquals(0.5f, PlaybackSpeedPolicy.clampToSupported(0.1f)) // below min
        assertEquals(1f, PlaybackSpeedPolicy.clampToSupported(1f))     // exact
        assertEquals(1f, PlaybackSpeedPolicy.clampToSupported(Float.NaN))            // non-finite -> default
        assertEquals(1f, PlaybackSpeedPolicy.clampToSupported(Float.POSITIVE_INFINITY))
    }

    @Test fun `isValid only for listed speeds`() {
        assertTrue(PlaybackSpeedPolicy.isValid(0.5f))
        assertTrue(PlaybackSpeedPolicy.isValid(2f))
        assertFalse(PlaybackSpeedPolicy.isValid(4f))
        assertFalse(PlaybackSpeedPolicy.isValid(1.25f))
    }

    @Test fun `label is locale-aware decimal with multiplier`() {
        assertEquals("1×", PlaybackSpeedPolicy.label(1f, Locale.US))
        assertEquals("2×", PlaybackSpeedPolicy.label(2f, Locale.US))
        assertEquals("1.5×", PlaybackSpeedPolicy.label(1.5f, Locale.US))
        assertEquals("0.5×", PlaybackSpeedPolicy.label(0.5f, Locale.US))
        assertEquals("1,5×", PlaybackSpeedPolicy.label(1.5f, Locale("es")))
        assertEquals("0,5×", PlaybackSpeedPolicy.label(0.5f, Locale("es")))
    }
}
