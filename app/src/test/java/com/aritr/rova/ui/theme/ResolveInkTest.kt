package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ResolveInk] — the one per-backing "hue as ink" resolver (APPX-D).
 *
 * Reference algorithm: `docs/design/warnings-recovery.html` `resolveInk` (`:1252`–`:1264`).
 * Direction is chosen by the BACKING's luminance:
 *  - dark backing  → committed lighten derivation `mix(hue, mix, top)`; does NOT consult `target`
 *  - light backing → `deepen(hue, t)` at the minimum `t ∈ {0, .12, … .6}` clearing `target`
 */
class ResolveInkTest {

    private val hard = RovaWarnings.hard
    private val soft = RovaWarnings.soft
    private val pin = RovaTrustTokens.pinSurface

    /** `top` for the pinned sites: media-ink over the opaque pin surface. */
    private fun pinnedTop(): Color =
        overRgb(WHITE_RGB, RovaTrustTokens.mediaInk.alpha.toDouble(), pinRgb()).toColor()

    // ── constants are the spec's, verbatim ───────────────────────────────────

    @Test fun constants_matchTheFrozenSpec() {
        assertEquals(0.12, ResolveInk.STEP, 0.0)
        assertEquals(0.6, ResolveInk.MAX_T, 0.0)
        assertEquals(0.18, ResolveInk.DARK_BACKING, 0.0)
        assertEquals(0.62, ResolveInk.MIX_LABEL, 0.0)
        assertEquals(0.72, ResolveInk.MIX_ACCENT, 0.0)
        assertEquals(0.0, ResolveInk.MIX_MARK, 0.0)
        assertEquals(4.5, ResolveInk.TARGET_TEXT, 0.0)
        assertEquals(3.0, ResolveInk.TARGET_MARK, 0.0)
    }

    // ── dark branch ──────────────────────────────────────────────────────────

    @Test fun darkBacking_lightens_towardTop_byMixFraction() {
        val top = pinnedTop()
        val ink = ResolveInk.of(hard, pin, ResolveInk.TARGET_TEXT, top, ResolveInk.MIX_LABEL)

        assertEquals(ResolveInk.Direction.LIGHTEN, ink.direction)
        assertEquals(0.0, ink.t, 1e-12)
        assertEquals(overRgb(hard.rgb(), ResolveInk.MIX_LABEL, top.rgb()).toColor(), ink.color)
    }

    /**
     * The lighten branch is committed, not searched: an impossible target changes nothing.
     * That it clears its real target is proven by `TrustContrastSweepTest`, not enforced here.
     */
    @Test fun darkBacking_doesNotConsultTarget() {
        val top = pinnedTop()
        val real = ResolveInk.of(hard, pin, ResolveInk.TARGET_TEXT, top, ResolveInk.MIX_LABEL)
        val absurd = ResolveInk.of(hard, pin, 21.0, top, ResolveInk.MIX_LABEL)

        assertEquals(real, absurd)
        assertEquals(ResolveInk.Direction.LIGHTEN, absurd.direction)
    }

    /** Marks pass `mix = 0` and must come back as the raw locked hue (no desaturation). */
    @Test fun darkBacking_markMixZero_returnsRawHue() {
        val ink = ResolveInk.of(hard, pin, ResolveInk.TARGET_MARK, pinnedTop(), ResolveInk.MIX_MARK)

        assertEquals(ResolveInk.Direction.LIGHTEN, ink.direction)
        assertEquals(hard, ink.color)
    }

    // ── the DARK_BACKING boundary ────────────────────────────────────────────

    @Test fun darkBackingBoundary_gray117_lightens_gray118_deepens() {
        val below = Color(117, 117, 117)   // relative luminance 0.1779 < 0.18
        val above = Color(118, 118, 118)   // relative luminance 0.1812 ≥ 0.18

        assertTrue(lumRgb(below.rgb()) < ResolveInk.DARK_BACKING)
        assertTrue(lumRgb(above.rgb()) >= ResolveInk.DARK_BACKING)

        assertEquals(
            ResolveInk.Direction.LIGHTEN,
            ResolveInk.of(hard, below, ResolveInk.TARGET_MARK, Color.White, ResolveInk.MIX_MARK).direction,
        )
        assertEquals(
            ResolveInk.Direction.DEEPEN,
            ResolveInk.of(hard, above, ResolveInk.TARGET_MARK, Color.White, ResolveInk.MIX_MARK).direction,
        )
    }

    // ── light branch ─────────────────────────────────────────────────────────

    /**
     * The tightest cell in the whole frozen matrix: Daylight, `Mark · stripchip · hard`.
     * Raw hue reads 2.40:1 on its own 20% chip fill; one deepen step (t = 0.12) reaches 3.0055:1.
     */
    @Test fun lightBacking_selectsMinimumTClearingTarget() {
        val daylight = rovaPalettes.getValue(ThemeSelection.DAYLIGHT)
        val tint = tintOf(daylight, hard)
        val backing = overRgb(hard.rgb(), RovaTrustTokens.sevChipFillAlpha.toDouble(), tint)
        val top = tHighOver(daylight, tint).toColor()

        val ink = ResolveInk.of(hard, backing.toColor(), ResolveInk.TARGET_MARK, top, ResolveInk.MIX_MARK)

        assertEquals(ResolveInk.Direction.DEEPEN, ink.direction)
        assertEquals(ResolveInk.STEP, ink.t, 1e-9)
        assertEquals(Color(210, 60, 60), ink.color)

        // clears the bar …
        assertTrue(ratioRgb(ink.color.rgb(), backing) >= ResolveInk.TARGET_MARK)
        // … and the step below it does not (minimality, not merely sufficiency)
        assertTrue(ratioRgb(hard.rgb(), backing) < ResolveInk.TARGET_MARK)
    }

    /** A deepen backing on which the raw hue already clears returns `t = 0`, still DEEPEN. */
    @Test fun lightBacking_rawHueAlreadyClears_returnsTZeroDeepen() {
        val daylight = rovaPalettes.getValue(ThemeSelection.DAYLIGHT)
        val backing = tintOf(daylight, hard).toColor()   // the `set` site's mark backing
        val top = tHighOver(daylight, backing.rgb()).toColor()

        val ink = ResolveInk.of(hard, backing, ResolveInk.TARGET_MARK, top, ResolveInk.MIX_MARK)

        assertEquals(ResolveInk.Direction.DEEPEN, ink.direction)
        assertEquals(0.0, ink.t, 1e-9)
        assertEquals(hard, ink.color)
    }

    @Test fun lightBacking_unreachableTarget_fallsBackToMaxT() {
        val ink = ResolveInk.of(soft, Color.White, 21.0, Color.Black, ResolveInk.MIX_MARK)

        assertEquals(ResolveInk.Direction.DEEPEN, ink.direction)
        assertEquals(ResolveInk.MAX_T, ink.t, 1e-9)
        assertEquals(Color(100, 76, 14), ink.color)   // deepen(#FBBF24, 0.6)
    }

    // ── the property the spec asserts in prose ───────────────────────────────

    /**
     * "Only Daylight takes the deepen branch" (HTML `:1250`) — swept over every site × role ×
     * severity × media frame × palette (2016 probes).
     *
     * The property is about the BACKING, not the palette: Daylight's four *pinned* sites still
     * lighten, because their backing is the near-black pin surface / capsule regardless of theme.
     * So: deepen ⟹ Daylight, and Daylight's four *themed* sites always deepen.
     */
    @Test fun onlyDaylightDeepens_andOnlyAtItsThemedSites() {
        val pinnedSites = setOf("sheeticon", "pinchip", "banner", "snooze")
        var deepenProbes = 0
        var probes = 0

        for (palette in rovaPalettes.values) {
            for (frame in MediaFrame.entries) {
                for (site in INK_SITES) {
                    for (severity in Severity.entries) {
                        val hue = severity.color
                        val top = site.top(palette, hue, frame.rgb).toColor()

                        val directions = buildList {
                            site.dot?.let {
                                add(ResolveInk.of(hue, it(palette, hue, frame.rgb).toColor(), ResolveInk.TARGET_MARK, top, ResolveInk.MIX_MARK).direction)
                            }
                            site.lbl?.let {
                                add(ResolveInk.of(hue, it(palette, hue, frame.rgb).toColor(), ResolveInk.TARGET_TEXT, top, ResolveInk.MIX_LABEL).direction)
                            }
                            site.acc?.let {
                                add(ResolveInk.of(palette.accent, it(palette, hue, frame.rgb).toColor(), ResolveInk.TARGET_TEXT, top, ResolveInk.MIX_ACCENT).direction)
                            }
                        }

                        val expectDeepen = palette.isLight && site.key !in pinnedSites
                        for (direction in directions) {
                            probes++
                            val deepened = direction == ResolveInk.Direction.DEEPEN
                            if (deepened) deepenProbes++
                            assertEquals(
                                "palette=${palette.id} frame=$frame site=${site.key} sev=$severity",
                                expectDeepen,
                                deepened,
                            )
                        }
                    }
                }
            }
        }

        assertEquals(2016, probes)
        // Daylight only, themed sites only: 3 frames × 4 severities × (chip 2 + stripchip 2 + set 2 + recov 2)
        assertEquals(96, deepenProbes)
    }
}
