package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * player-accessibility.html §09 / player-core.html §04 (:521) — the pure pick
 * behind the Player's reduce-transparency pill wiring. Under OS
 * reduce-transparency the pinned transport-glyph pills "render fully opaque"
 * (§11) via the same [RovaTrustTokens.pinContainerAlphaFor] path the Trust
 * surfaces use; default (signal off) stays byte-identical to `--glyph-fill`.
 *
 * Mirrors `RovaTrustTokensTest.pinContainerAlphaFor…` — a Compose-free unit pin,
 * so the seam is provable without a UI test. Mutation-proof: flipping the
 * default branch to opaque, or the opaque branch back to translucent, fails here.
 */
class PlayerPinnedPillReduceTransparencyTest {

    @Test
    fun default_signal_off_is_byte_identical_to_glyphFill() {
        assertEquals(PlayerTokens.glyphFill, PlayerTokens.pinnedPillFill(false))
    }

    @Test
    fun reduceTransparency_makes_the_pill_fully_opaque() {
        val opaque = PlayerTokens.pinnedPillFill(true)
        // "over-media substrates render fully opaque" (§11): alpha → 1.0.
        assertEquals(1f, opaque.alpha, 1e-4f)
        // And it is no longer the translucent glass — the see-through is removed.
        assertNotEquals(PlayerTokens.glyphFill, opaque)
    }

    @Test
    fun opaque_pill_is_the_glyphFill_over_the_pinned_surface() {
        // The opaque realisation composites the SAME --glyph-fill over the opaque
        // pinned surface (no new token); so it reads as a lifted-dark pill, not a
        // solid white one (a literal alpha→1.0 on white glass would be wrong).
        val opaque = PlayerTokens.pinnedPillFill(true)
        assertTrue("opaque pill must stay dark (lifted pinSurface)", opaque.red < 0.5f)
        assertTrue("opaque pill must stay dark (lifted pinSurface)", opaque.green < 0.5f)
        assertTrue("opaque pill must stay dark (lifted pinSurface)", opaque.blue < 0.5f)
    }
}
