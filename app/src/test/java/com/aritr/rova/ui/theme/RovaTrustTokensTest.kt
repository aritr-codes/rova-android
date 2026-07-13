package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RovaTrustTokensTest {

    @Test fun sheetCornerRadius_is_26dp() {
        assertEquals(26.dp, RovaTrustTokens.sheetCornerRadius)
    }

    @Test fun sheetIconSize_is_56dp() {
        assertEquals(56.dp, RovaTrustTokens.sheetIconSize)
    }

    @Test fun sheetIconCornerRadius_is_18dp() {
        assertEquals(18.dp, RovaTrustTokens.sheetIconCornerRadius)
    }

    // M6 deleted the dead `sheetTitleSize`/`sheetBodySize` tokens — the sheet title/body
    // now carry the spec type scale (15sp/12.5sp) inline. The old pins are retired here.

    @Test fun sheetCtaHeight_is_48dp() {
        // Frozen `.cta{min-height:var(--target)}` = 48 (M6 lifted 46→48; used as a min-height).
        assertEquals(48.dp, RovaTrustTokens.sheetCtaHeight)
    }

    @Test fun sevChipRadius_is_10dp() {
        // Frozen `.sevchip{border-radius:var(--r-sm)}` = 10 (M6; was a full pill pre-freeze).
        assertEquals(10.dp, RovaTrustTokens.sevChipRadius)
    }

    @Test fun whyRowCornerRadius_is_10dp() {
        // Frozen `.whyrow{border-radius:var(--r-sm)}` = 10 (M6 lifted 11→10).
        assertEquals(10.dp, RovaTrustTokens.whyRowCornerRadius)
    }

    @Test fun secondaryCtaStrokeAlpha_is_0_12() {
        // Frozen ghost border `color-mix(ink-high 12%)` (M6 lifted .05→.12).
        assertEquals(0.12f, RovaTrustTokens.secondaryCtaStrokeAlpha, 1e-4f)
    }

    @Test fun recoveryCardCornerRadius_is_18dp() {
        assertEquals(18.dp, RovaTrustTokens.recoveryCardCornerRadius)
    }

    @Test fun failBoxCornerRadius_is_10dp() {
        // Frozen `.failbox{border-radius:var(--r-sm)}` = 10 (M9).
        assertEquals(10.dp, RovaTrustTokens.failBoxCornerRadius)
    }

    @Test fun recoveryProgressCellHeight_is_7dp() {
        assertEquals(7.dp, RovaTrustTokens.recoveryProgressCellHeight)
    }

    @Test fun iconGlow_returns_nonNull_brush_for_each_severity() {
        val r = 100f
        assertNotNull(RovaTrustTokens.iconGlow(RovaWarnings.hard, r))
        assertNotNull(RovaTrustTokens.iconGlow(RovaWarnings.soft, r))
        assertNotNull(RovaTrustTokens.iconGlow(RovaWarnings.advisory, r))
        assertNotNull(RovaTrustTokens.iconGlow(RovaWarnings.escalating, r))
    }

    @Test fun recoveryGlow_returns_nonNull_brush_for_each_severity() {
        assertNotNull(RovaTrustTokens.recoveryGlow(RovaWarnings.hard))
        assertNotNull(RovaTrustTokens.recoveryGlow(RovaWarnings.soft))
    }

    @Test fun iconGlow_and_recoveryGlow_are_distinct_brush_types() {
        // iconGlow is radial; recoveryGlow is vertical. Same severity ≠ same Brush.
        assertTrue(
            "iconGlow and recoveryGlow must produce different Brush instances",
            RovaTrustTokens.iconGlow(RovaWarnings.hard, 100f) !== RovaTrustTokens.recoveryGlow(RovaWarnings.hard)
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // Trust System V1 token foundation (M1) — value pins transcribed from
    // the frozen spec `docs/design/warnings-recovery.html` §02 (:74–:89).
    // These tokens have NO production consumer yet (M1 is additive); the
    // pins are the only thing standing between a typo and a silent visual
    // regression when M4–M8 wire them up.
    // ══════════════════════════════════════════════════════════════════

    // ── Family 3 · locked pinned / over-media ─────────────────────────

    @Test fun pinSurface_is_0B0D14() {
        // HTML :79 `--pin-surface:#0B0D14` — unified the banner's and the
        // recovery card's two divergent raw hexes.
        assertEquals(Color(0xFF0B0D14), RovaTrustTokens.pinSurface)
    }

    @Test fun pinContainerAlpha_is_0_94() {
        // HTML :84. One alpha for every pinned container floating OVER MEDIA.
        assertEquals(0.94f, RovaTrustTokens.pinContainerAlpha, 1e-4f)
    }

    @Test fun pinContainerAlphaFor_is_opaque_under_reduceTransparency_else_default() {
        // M10 (frozen spec §11 :853): over-media substrates render fully opaque under
        // the reduce-transparency signal; the default (signal off) is byte-identical to
        // the pre-M10 pinContainerAlpha (.94).
        assertEquals(1f, RovaTrustTokens.pinContainerAlphaFor(true), 1e-4f)
        assertEquals(
            RovaTrustTokens.pinContainerAlpha,
            RovaTrustTokens.pinContainerAlphaFor(false),
            1e-4f,
        )
    }

    @Test fun mediaInk_trio_are_white_at_94_48_and_55_percent() {
        // HTML :85–:87.
        assertEquals(Color.White.copy(alpha = 0.94f), RovaTrustTokens.mediaInk)
        assertEquals(Color.White.copy(alpha = 0.48f), RovaTrustTokens.mediaInkDim)
        assertEquals(Color.White.copy(alpha = 0.55f), RovaTrustTokens.mediaInkBody)
    }

    @Test fun mediaEdgeTop_is_white_at_12_percent() {
        // HTML :89 `--media-edge-top:rgba(255,255,255,.12)`.
        assertEquals(Color.White.copy(alpha = 0.12f), RovaTrustTokens.mediaEdgeTop)
    }

    // ── Family 2 · locked severity ────────────────────────────────────

    @Test fun severityCtaInk_is_1A1A1A() {
        // HTML :74. Graduated from a call-site literal to a named locked
        // token. Zero visual delta — the literal it names is unchanged.
        assertEquals(Color(0xFF1A1A1A), RovaTrustTokens.severityCtaInk)
    }

    @Test fun severityCtaInk_clears_AA_on_all_four_severity_fills() {
        // HTML :70–:73 — "WARN-02: white-on-severity reads 1.67–3.76:1 and
        // fails; this ink clears 4.5:1 on all four fills." This is the whole
        // reason the token exists, so it is pinned as a contract, not a value.
        val ink = RovaTrustTokens.severityCtaInk
        listOf(
            "hard" to RovaWarnings.hard,
            "soft" to RovaWarnings.soft,
            "escalating" to RovaWarnings.escalating,
            "advisory" to RovaWarnings.advisory,
        ).forEach { (name, fill) ->
            val ratio = ContrastMath.contrastRatio(
                ContrastMath.relativeLuminance(ink.r8(), ink.g8(), ink.b8()),
                ContrastMath.relativeLuminance(fill.r8(), fill.g8(), fill.b8()),
            )
            assertTrue(
                "severityCtaInk on $name reads %.2f:1, below the 4.5:1 AA bar".format(ratio),
                ratio >= 4.5
            )
        }
    }

    @Test fun mediaInk_trio_clear_AA_on_the_pinned_capsule_over_the_worst_media_frame() {
        // The capsule is pinSurface @ pinContainerAlpha composited over live
        // media. The frozen inspector sweeps three reference frames; `white`
        // (255,255,255) is the worst case for a dark capsule. Every over-media
        // ink must still clear 4.5:1 against the capsule it sits on.
        val frame = intArrayOf(255, 255, 255)
        val pin = RovaTrustTokens.pinSurface
        val capsule = ContrastMath.compositeAlphaOver(
            pin.r8(), pin.g8(), pin.b8(),
            RovaTrustTokens.pinContainerAlpha.toDouble(),
            frame[0], frame[1], frame[2],
        )
        listOf(
            "mediaInk" to RovaTrustTokens.mediaInk,
            "mediaInkDim" to RovaTrustTokens.mediaInkDim,
            "mediaInkBody" to RovaTrustTokens.mediaInkBody,
        ).forEach { (name, ink) ->
            val ratio = ContrastMath.contrastRatioForAlpha(
                255, 255, 255, ink.alpha.toDouble(),
                capsule[0], capsule[1], capsule[2],
            )
            assertTrue(
                "$name on the pinned capsule reads %.2f:1, below the 4.5:1 AA bar".format(ratio),
                ratio >= 4.5
            )
        }
    }

    // ── Family 1 · identity · DERIVED surfaceHi ───────────────────────

    @Test fun surfaceHi_derives_mix_of_white_8_percent_over_surfaceBase() {
        // HTML :35 + :1340 `p.surfaceHi ?? rgbToHex(mixc([255,255,255],.08,bgB))`.
        // RovaPalette has NO surfaceHi member — the recovery card stops being a
        // pinned near-black island and adopts an elevated themed surface.
        // Expected values recomputed independently from each palette's bgBottom,
        // which the HTML's PALETTES table (:1181–:1196) matches verbatim.
        val expected = mapOf(
            ThemeSelection.AURORA to Color(0xFF272934),
            ThemeSelection.TIDE to Color(0xFF212C31),
            ThemeSelection.JADE to Color(0xFF1F2E2A),
            ThemeSelection.DUSK to Color(0xFF312623),
            ThemeSelection.ECLIPSE to Color(0xFF141414),
            ThemeSelection.BLOSSOM to Color(0xFF302532),
            ThemeSelection.CORAL to Color(0xFF312823),
            ThemeSelection.MEADOW to Color(0xFF252B21),
            ThemeSelection.COBALT to Color(0xFF212541),
            ThemeSelection.ORCHID to Color(0xFF30232B),
            ThemeSelection.GRAPHITE to Color(0xFF212225),
        )
        expected.forEach { (sel, want) ->
            assertEquals(
                "surfaceHi mismatch for $sel",
                want,
                RovaTrustTokens.surfaceHi(rovaPalettes.getValue(sel)),
            )
        }
    }

    @Test fun surfaceHi_returns_the_explicit_member_for_the_light_palette() {
        // HTML :1190 `surface:'#F4F1EA', surfaceHi:'#FFFFFF'` — Daylight is the
        // only palette carrying an explicit member, and it is NOT the 8% mix.
        val daylight = rovaPalettes.getValue(ThemeSelection.DAYLIGHT)
        assertTrue("Daylight must remain the light palette", daylight.isLight)
        assertEquals(Color.White, RovaTrustTokens.surfaceHi(daylight))
    }

    @Test fun surfaceHi_is_strictly_elevated_above_surfaceBase_for_every_palette() {
        // The token's reason for existing: an ELEVATED container. A derivation
        // that returned surfaceBase (mix factor 0) would silently flatten the
        // recovery card, and every per-palette pin above would still pass if
        // the palette's bgBottom were black. This catches that class of bug.
        rovaPalettes.values.forEach { p ->
            val hi = RovaTrustTokens.surfaceHi(p)
            if (!p.isLight) {
                val hiLum = ContrastMath.relativeLuminance(hi.r8(), hi.g8(), hi.b8())
                val baseLum = ContrastMath.relativeLuminance(
                    p.surfaceBase.r8(), p.surfaceBase.g8(), p.surfaceBase.b8(),
                )
                assertTrue("surfaceHi must be lighter than surfaceBase for ${p.id}", hiLum > baseLum)
            }
        }
    }

    @Test fun surfaceHiMixFraction_is_8_percent() {
        assertEquals(0.08f, RovaTrustTokens.surfaceHiMixFraction, 1e-4f)
    }

    // ── Snooze chip geometry (M4) — new named tokens land with their first ──
    // consumer (WarningSnoozeChip). Values from the frozen spec's `.snooze .pill`
    // rule (warnings-recovery.html :326–:327): height 34px, padding 0 s4 (16px),
    // gap s2 (8px). P4: non-primitive padding/gap/height come from named tokens.
    @Test fun snoozeChipPillHeight_is_34dp() {
        assertEquals(34.dp, RovaTrustTokens.snoozeChipPillHeight)
    }

    @Test fun snoozeChipPaddingH_is_16dp() {
        assertEquals(16.dp, RovaTrustTokens.snoozeChipPaddingH)
    }

    @Test fun snoozeChipGap_is_8dp() {
        assertEquals(8.dp, RovaTrustTokens.snoozeChipGap)
    }

    // ── Snooze dot pulse motion (M5) — retranscribed to the frozen spec's
    // `@keyframes pulse` (warnings-recovery.html :336–:337): `pulse 1.6s
    // var(--ease-std)` with `50%{opacity:.45}`. M4 shipped .60 / 1.5s tween as
    // tracked debt; M5 closes it. Easing is `RovaMotion.easeStandard`.
    @Test fun snoozeChipDotPulseAlpha_is_0_45() {
        assertEquals(0.45f, RovaTrustTokens.snoozeChipDotPulseAlpha, 1e-4f)
    }

    @Test fun snoozeChipPulsePeriodMs_is_1600() {
        assertEquals(1600, RovaTrustTokens.snoozeChipPulsePeriodMs)
    }
}

/** sRGB 8-bit channel accessors — the units [ContrastMath] speaks. */
private fun Color.r8(): Int = (red * 255f).roundToInt()
private fun Color.g8(): Int = (green * 255f).roundToInt()
private fun Color.b8(): Int = (blue * 255f).roundToInt()
