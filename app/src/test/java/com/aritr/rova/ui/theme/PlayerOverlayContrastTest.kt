package com.aritr.rova.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player shell's **scrim-composite contrast pin** — `docs/design/player-core.html`
 * §07 / §09. After Phase 1 token migration, the player's over-media chrome no
 * longer hand-tunes contrast per `Color.White.copy(alpha=…)` literal; it rides
 * the locked over-media ink family ([RovaTrustTokens.mediaInk] / `mediaInkDim` /
 * `mediaInkBody`) + [PlayerTokens]. This test is the **single** place that
 * enforces AA for that family, replacing the eleven per-literal decisions.
 *
 * **Why a NEW pin and not the Trust sweep.** `TrustContrastSweepTest` proves the
 * same inks against *opaque* pinned surfaces (the modal sheet composites over
 * nothing). The player inks instead sit over two *translucent* scrim bands over
 * arbitrary video, and the dimmed inks sit exactly where the gradient has faded.
 * So the shell requires its own composite pin — modelled on
 * [TokenContrastTest]'s `mediaProgress` test (a token asserted against a scrim
 * composite), not borrowed from the opaque sweep.
 *
 * **Background & the honest worst case.** White ink's worst case is the
 * *lightest* backing. The two localized bands (top black .82→0, bottom 0→.90)
 * only ever DARKEN the composite from the footage, so they strictly *improve*
 * white-ink contrast; the true worst case within the design target is the
 * **faded band edge**, where the scrim contributes ≈0 and the backing is just
 * the footage. As with [OverlayContrastTest] / [TokenContrastTest], these assert
 * over the documented **dark-scene reference** (`#0E1216`) — the indoor/night
 * case the dark over-media chrome is designed for. Over arbitrarily bright
 * footage *at the faded edge* no white ink can pass; that is the acknowledged
 * scene-variable caveat the scrim bands exist to mitigate. But the dimmed inks
 * do NOT sit past the band — they sit INSIDE it, so the spec's actual obligation
 * (player-accessibility.html §09 / player-core §07 :623 "band-over-bright-video
 * composite") is separately pinned below over the frozen worst-case-bright
 * specimen footage (`#3A4C66`, player-core.html :201): the band darkens even that
 * brightest stop enough that the inks clear AA where they render. The player ink
 * family is locked white (not themed), so there is no ×palette axis here — unlike
 * `mediaProgress`.
 *
 * Reads the *actual* token alphas via [ContrastMath], so lowering any token back
 * below the bar re-breaks this test.
 */
class PlayerOverlayContrastTest {

    private val darkRef = intArrayOf(0x0E, 0x12, 0x16) // MidnightSurface dark-scene reference

    // player-core.html :201 — the specimen's brightest footage stop, the frozen
    // "worst case is bright content; we show a mid grey-blue" reference.
    private val brightRef = intArrayOf(0x3A, 0x4C, 0x66)

    private fun whiteInkRatioOver(alpha: Float, bg: IntArray): Double =
        ContrastMath.contrastRatioForAlpha(255, 255, 255, alpha.toDouble(), bg[0], bg[1], bg[2])

    /** A black scrim band at [scrimAlpha] composited over an opaque footage backing. */
    private fun bandOver(footage: IntArray, scrimAlpha: Double): IntArray =
        ContrastMath.compositeAlphaOver(0, 0, 0, scrimAlpha, footage[0], footage[1], footage[2])

    @Test
    fun `over-media text inks clear AA at the faded band edge (worst case)`() {
        // The faded gradient edge is where --media-ink-dim / --media-ink-body sit
        // (§07): the band contributes ≈0 there, so the composite is the dark-scene
        // ref itself. This is the shell's contrast guarantee — one place, not eleven.
        val inks = mapOf(
            "mediaInk (.94)" to RovaTrustTokens.mediaInk.alpha,
            "mediaInkDim (.48)" to RovaTrustTokens.mediaInkDim.alpha,
            "mediaInkBody (.55)" to RovaTrustTokens.mediaInkBody.alpha,
        )
        inks.forEach { (name, alpha) ->
            val ratio = whiteInkRatioOver(alpha, darkRef)
            assertTrue(
                "$name must meet 4.5:1 at the faded band edge (was ${"%.2f".format(ratio)}:1)",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun `dimmed inks stay AA under partial band coverage`() {
        // Belt-and-suspenders: even a thin band (.30, far below the .82/.90 the
        // bands actually reach) over the dark-scene ref keeps the two dimmed inks
        // — the ones §07 names — above 4.5:1. Strictly higher than the faded-edge
        // case above, confirming the band only helps.
        val bg = bandOver(darkRef, 0.30)
        listOf(
            "mediaInkDim (.48)" to RovaTrustTokens.mediaInkDim.alpha,
            "mediaInkBody (.55)" to RovaTrustTokens.mediaInkBody.alpha,
        ).forEach { (name, alpha) ->
            val ratio = whiteInkRatioOver(alpha, bg)
            assertTrue(
                "$name must meet 4.5:1 under a .30 band (was ${"%.2f".format(ratio)}:1)",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun `dimmed inks clear AA over the band composited over worst-case bright footage`() {
        // player-accessibility.html §09 / player-core §07 (:201, :623) — the OWED
        // scrim-composite pin, extended to BRIGHT footage (not just the dark ref).
        // White ink's worst case is the lightest backing, but the dimmed inks §07
        // names sit INSIDE the localized bands (subtitle in the top .82 band,
        // InfoRow/body in the bottom .90 band), not past them — and the band
        // darkens even the brightest specimen stop (#3A4C66) enough to clear 4.5:1.
        // (The raw stop with the band faded to ~0 cannot pass — that is the
        // acknowledged scene caveat the bands exist to mitigate.)
        val cases = listOf(
            Triple("mediaInkDim (.48) · top band .82", RovaTrustTokens.mediaInkDim.alpha, bandOver(brightRef, 0.82)),
            Triple("mediaInkBody (.55) · bottom band .90", RovaTrustTokens.mediaInkBody.alpha, bandOver(brightRef, 0.90)),
        )
        cases.forEach { (name, alpha, bg) ->
            val ratio = whiteInkRatioOver(alpha, bg)
            assertTrue(
                "$name must meet 4.5:1 over worst-case bright footage (was ${"%.2f".format(ratio)}:1)",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun `timeline fill meets the 3 to 1 non-text floor over the dark-scene reference`() {
        // SC 1.4.11: the playhead fill (PlayerTokens.barFill, .90) is a functional
        // UI graphic (it communicates position), not decoration. The track (.18)
        // and ticks (.40) are decorative context; the fill is the load-bearing mark.
        val ratio = whiteInkRatioOver(PlayerTokens.barFill.alpha, darkRef)
        assertTrue("timeline fill must meet 3:1 (was ${"%.2f".format(ratio)}:1)", ratio >= 3.0)
    }
}
