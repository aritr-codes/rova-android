package com.aritr.rova.ui.warnings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.aritr.rova.ui.theme.ContrastMath
import com.aritr.rova.ui.theme.ResolveInk
import com.aritr.rova.ui.theme.RovaWarnings
import com.aritr.rova.ui.theme.ThemeSelection
import com.aritr.rova.ui.theme.quietTextColor
import com.aritr.rova.ui.theme.rovaPalettes
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M7 themed-host ink wiring (Trust System V1) — the `stripchip` + `set` INK_SITES
 * as the History strip and Settings chip actually paint them.
 *
 * The 2808-assertion `TrustContrastSweepTest` already proves every resolved ink on
 * every palette × frame. This test pins the composable-level derivation instead:
 * that [ThemedHostInk] feeds `ResolveInk` the SAME backings the sweep models
 * (`tintOf` / sev-chip fill over tint), so the shipped surfaces == the swept world,
 * and that the two palette-blindness traps §06 removes are actually removed:
 *
 *  - **Daylight deepen** — on the one light palette the sevchip label, the sevchip
 *    dot, the settings dot and the "Fix" accent-as-text all take the DEEPEN branch
 *    and clear their AA / mark targets over the tinted chip. A fixed severity/accent
 *    mix reads below AA here (proven in `TrustContrastSweepTest`); the resolver fixes it.
 *  - **Solid quiet body on light** — [ThemedHostInk.themedBody] returns the SOLID
 *    palette dim ink on a light scheme (alpha dropped, per `quietTextColor`), which
 *    clears AA; the naive `onSurfaceVariant @ its own alpha` reads ~4.25:1 on Daylight
 *    and FAILS. This test pins both so a "simplify to rovaQuietText" regresses RED.
 */
class ThemedHostInkTest {

    private val daylight = rovaPalettes.getValue(ThemeSelection.DAYLIGHT)
    private val aurora = rovaPalettes.getValue(ThemeSelection.AURORA)

    private val severities = listOf(
        RovaWarnings.hard, RovaWarnings.soft, RovaWarnings.advisory, RovaWarnings.escalating,
    )

    private fun Color.lum(): Double =
        ContrastMath.relativeLuminance(red * 255.0, green * 255.0, blue * 255.0)

    private fun ratio(fg: Color, bg: Color): Double =
        ContrastMath.contrastRatio(fg.lum(), bg.lum())

    // ── Daylight: every themed ink takes DEEPEN and clears its target ────────

    @Test fun daylight_stripChip_inks_clearTargets_overTheTintedChip() {
        severities.forEach { sev ->
            val ink = ThemedHostInk.forStrip(
                severity = sev,
                surface = daylight.surfaceBase,
                onSurface = daylight.textHigh,
                onSurfaceVariant = daylight.textDim,
                isDark = false,
            )
            val chipFill = sev.copy(alpha = ThemedHostInk.SEV_CHIP_FILL_ALPHA).compositeOver(ink.tint)
            assertTrue("strip label must clear AA on Daylight", ratio(ink.chipLabel, chipFill) >= ResolveInk.TARGET_TEXT)
            assertTrue("strip dot must clear the mark bar on Daylight", ratio(ink.chipDot, chipFill) >= ResolveInk.TARGET_MARK)
        }
    }

    @Test fun daylight_setChip_dotAndFix_clearTargets_overTheTint() {
        severities.forEach { sev ->
            val ink = ThemedHostInk.forSet(
                severity = sev,
                accent = daylight.accent,
                surface = daylight.surfaceBase,
                onSurface = daylight.textHigh,
            )
            assertTrue("settings dot must clear the mark bar on Daylight", ratio(ink.dot, ink.tint) >= ResolveInk.TARGET_MARK)
            assertTrue("\"Fix\" accent-as-text must clear AA on Daylight", ratio(ink.fix, ink.tint) >= ResolveInk.TARGET_TEXT)
        }
    }

    // ── Aurora (dark): MARK inks pass the raw locked hue straight through ─────

    @Test fun darkPalette_markInks_areTheRawSeverityHue_lightenPassthrough() {
        severities.forEach { sev ->
            val strip = ThemedHostInk.forStrip(sev, aurora.surfaceBase, aurora.textHigh, aurora.textDim, isDark = true)
            val set = ThemedHostInk.forSet(sev, aurora.accent, aurora.surfaceBase, aurora.textHigh)
            assertEquals("strip dot is the raw locked hue on a dark tint", sev, strip.chipDot)
            assertEquals("settings dot is the raw locked hue on a dark tint", sev, set.dot)
        }
    }

    // ── the quiet-body finding: solid on light, dimmed on dark ───────────────

    @Test fun themedBody_isSolidOnLight_andClearsAA_whereTheNaiveDimmedInkFails() {
        severities.forEach { sev ->
            val tint = ThemedHostInk.tint(sev, daylight.surfaceBase)
            val solid = ThemedHostInk.themedBody(isDark = false, onSurfaceVariant = daylight.textDim)
            // The naive path: onSurfaceVariant painted at its OWN alpha (what rovaQuietText
            // returns on a light scheme, because it does not strip the variant's alpha).
            val dimmed = daylight.textDim.compositeOver(tint)

            assertEquals("body must be solid (alpha dropped) on a light scheme", 1f, solid.alpha)
            assertTrue("solid body clears AA on Daylight", ratio(solid, tint) >= ResolveInk.TARGET_TEXT)
            assertTrue(
                "the naive dimmed body must FAIL AA on Daylight (the trap M7 avoids)",
                ratio(dimmed, tint) < ResolveInk.TARGET_TEXT,
            )
        }
    }

    @Test fun themedBody_onDark_isTheVariantAtItsOwnAlpha() {
        val expected = quietTextColor(isDark = true, onSurfaceVariant = aurora.textDim.copy(alpha = 1f), dimAlpha = aurora.textDim.alpha)
        assertEquals(expected, ThemedHostInk.themedBody(isDark = true, onSurfaceVariant = aurora.textDim))
    }

    // ── the tint IS color-mix(sev 8%, surface) ───────────────────────────────

    @Test fun tint_isSevEightPercentOverSurface() {
        val sev = RovaWarnings.hard
        val expected = sev.copy(alpha = 0.08f).compositeOver(aurora.surfaceBase)
        assertEquals(expected, ThemedHostInk.tint(sev, aurora.surfaceBase))
    }

    @Suppress("unused")
    private fun Color.r8(): Int = (red * 255f).roundToInt()
}
