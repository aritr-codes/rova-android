package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Per-theme WCAG 2.2 AA contrast (ADR-0028 §4). For every palette we composite
 * the role's glass fill over the palette's darkest background stop to get the
 * REAL resolved surface, then composite each foreground (text/accent) over that
 * surface and assert text >= 4.5:1 and selected-control/border >= 3:1 — in both
 * the blur path and the API<31 solid-fallback path.
 */
class ThemeContrastTest {

    private fun rgb(c: Color) = Triple(
        (c.red * 255).roundToInt(),
        (c.green * 255).roundToInt(),
        (c.blue * 255).roundToInt(),
    )

    /** Darkest stop of a palette's vertical-gradient background (worst case for light text). */
    private fun sceneBottom(sel: ThemeSelection): Color = when (sel) {
        ThemeSelection.AURORA -> Color(0xFF141622)
        ThemeSelection.TIDE -> Color(0xFF0E1A1F)
        ThemeSelection.JADE -> Color(0xFF0C1C18)
        ThemeSelection.DUSK -> Color(0xFF1F1310)
        ThemeSelection.ECLIPSE -> Color(0xFF000000)
        ThemeSelection.DAYLIGHT -> Color(0xFFF4F1EA)
        ThemeSelection.BLOSSOM -> Color(0xFF1E1220)
        ThemeSelection.CORAL -> Color(0xFF1F1510)
        ThemeSelection.MEADOW -> Color(0xFF12190E)
        ThemeSelection.COBALT -> Color(0xFF0E1230)
        ThemeSelection.ORCHID -> Color(0xFF1E1019)
        ThemeSelection.GRAPHITE -> Color(0xFF0E0F12)
        ThemeSelection.FOLLOW_SYSTEM -> error("not a concrete palette")
    }

    /** Resolved surface = role fill (with its alpha) composited over the opaque scene bottom. */
    private fun resolvedSurface(fill: Color, sel: ThemeSelection): IntArray {
        val (fr, fg, fb) = rgb(fill)
        val (br, bg, bb) = rgb(sceneBottom(sel))
        return ContrastMath.compositeAlphaOver(fr, fg, fb, fill.alpha.toDouble(), br, bg, bb)
    }

    /** Contrast of a foreground (carrying its own alpha) over a resolved surface. */
    private fun ratioOver(fg: Color, surface: IntArray): Double {
        val (r, g, b) = rgb(fg)
        return ContrastMath.contrastRatioForAlpha(
            r, g, b, fg.alpha.toDouble(), surface[0], surface[1], surface[2],
        )
    }

    private val concrete = ThemeSelection.entries.filter { it != ThemeSelection.FOLLOW_SYSTEM }

    @Test
    fun `body text meets 4_5 to 1 over every palette surface (blur path)`() {
        concrete.forEach { sel ->
            val p = rovaPalettes.getValue(sel)
            val mat = GlassResolver.resolve(GlassEnvironment(p, 31, false), GlassRole.Card)
            val ratio = ratioOver(p.textHigh, resolvedSurface(mat.fill, sel))
            assertTrue("$sel body text ${"%.2f".format(ratio)}:1 < 4.5", ratio >= 4.5)
        }
    }

    @Test
    fun `body text meets 4_5 to 1 over the api30 solid-fallback surface`() {
        concrete.forEach { sel ->
            val p = rovaPalettes.getValue(sel)
            val mat = GlassResolver.resolve(GlassEnvironment(p, 30, false), GlassRole.Card)
            val ratio = ratioOver(p.textHigh, resolvedSurface(mat.fill, sel))
            assertTrue("$sel fallback body text ${"%.2f".format(ratio)}:1 < 4.5", ratio >= 4.5)
        }
    }

    @Test
    fun `selected-control accent meets 3 to 1 over every palette surface`() {
        concrete.forEach { sel ->
            val p = rovaPalettes.getValue(sel)
            val mat = GlassResolver.resolve(GlassEnvironment(p, 31, false), GlassRole.Card)
            val ratio = ratioOver(p.accent.copy(alpha = 1f), resolvedSurface(mat.fill, sel))
            assertTrue("$sel accent ${"%.2f".format(ratio)}:1 < 3.0", ratio >= 3.0)
        }
    }

    private fun lumColor(c: Color) =
        ContrastMath.relativeLuminance((c.red*255).roundToInt(), (c.green*255).roundToInt(), (c.blue*255).roundToInt())
    private fun ratioColor(fg: Color, bg: Color) = ContrastMath.contrastRatio(lumColor(fg), lumColor(bg))

    @Test
    fun `derived scheme on-colors clear 4_5 over their fills (all 12 palettes)`() {
        concrete.forEach { sel ->
            val s = PaletteColorScheme.from(rovaPalettes.getValue(sel))
            val pairs = listOf(
                "onBackground" to (s.onBackground to s.background),
                "onSurface" to (s.onSurface to s.surface),
                "onSurfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
                "onPrimary" to (s.onPrimary to s.primary),
                "onSecondary" to (s.onSecondary to s.secondary),
                "onTertiary" to (s.onTertiary to s.tertiary),
                "onPrimaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
                "onSecondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
                "onTertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
                "onErrorContainer" to (s.onErrorContainer to s.errorContainer),
                "onError" to (s.onError to s.error),
            )
            pairs.forEach { (name, fb) ->
                val r = ratioColor(fb.first, fb.second)
                assertTrue("$sel $name ${"%.2f".format(r)}:1 < 4.5", r >= 4.5)
            }
        }
    }

    @Test
    fun `body text clears 4_5 over every surfaceContainer rung (all 12 palettes)`() {
        concrete.forEach { sel ->
            val s = PaletteColorScheme.from(rovaPalettes.getValue(sel))
            listOf(
                "Dim" to s.surfaceDim, "Bright" to s.surfaceBright,
                "Lowest" to s.surfaceContainerLowest, "Low" to s.surfaceContainerLow,
                "Container" to s.surfaceContainer, "High" to s.surfaceContainerHigh,
                "Highest" to s.surfaceContainerHighest,
            ).forEach { (name, rung) ->
                val r = ratioColor(s.onSurface, rung)
                assertTrue("$sel onSurface over $name ${"%.2f".format(r)}:1 < 4.5", r >= 4.5)
            }
        }
    }

    @Test
    fun `primary and outline clear 3 to 1 over surface (all 12 palettes)`() {
        concrete.forEach { sel ->
            val s = PaletteColorScheme.from(rovaPalettes.getValue(sel))
            assertTrue("$sel primary/surface", ratioColor(s.primary, s.surface) >= 3.0)
            assertTrue("$sel outline/surface", ratioColor(s.outline, s.surface) >= 3.0)
        }
    }

    @Test
    fun `processing status color clears 3 to 1 over the pinned-dark record glass`() {
        // Both merge surfaces (Record-home StatusPill, recovery card) render on the
        // pinned-dark record route — neutral-dark regardless of theme. The branded
        // merge glyph uses the locked IconStatus.Processing -> RovaSemantics.escalating.
        // Substrate = the StatusPill glass fill (Black @ 40%) composited over the
        // neutral-dark record background. (Brighter video frames are the inherited
        // over-media caveat the glass chip handles per ADR-0031 §6 — not this slice.)
        val recordBg = NeutralDarkRecordPalette.surfaceBase
        val glassAlpha = RecordChromeTokens.glassFill.alpha.toDouble()  // Black @ 0.40
        val (br, bg, bb) = rgb(recordBg)
        val substrate = ContrastMath.compositeAlphaOver(0, 0, 0, glassAlpha, br, bg, bb)
        val ratio = ratioOver(RovaSemantics.escalating, substrate)
        assertTrue(
            "escalating ${"%.2f".format(ratio)}:1 < 3.0 over pinned-dark record glass",
            ratio >= 3.0,
        )
    }

    @Test
    fun `record FAB ghost glyphs (accent) clear 3 to 1 over the pinned-dark substrate (all 12 palettes)`() {
        // UI Phase 2 PR-2 (board-3 FB): the Ghost FAB states (Waiting hourglass,
        // Processing arc, Disabled rec_ring core) tint the glyph with the palette
        // ACCENT over a ghost container = accent @ 0.14 over the pinned record bg.
        // Adjacent cases above don't cover this exact composite, so assert it: the
        // accent glyph clears 3:1 (SC 1.4.11 graphical) over the ghost for every palette.
        val (br, bg, bb) = rgb(NeutralDarkRecordPalette.surfaceBase)
        concrete.forEach { sel ->
            val pinned = PinnedGlassEnvironment.forPinnedRoute(
                GlassEnvironment(rovaPalettes.getValue(sel), 31, false),
            ).palette
            val (ar, ag, ab) = rgb(pinned.accent)
            val ghost = ContrastMath.compositeAlphaOver(ar, ag, ab, 0.14, br, bg, bb)
            val ratio = ratioOver(pinned.accent.copy(alpha = 1f), ghost)
            assertTrue("$sel accent FAB glyph ${"%.2f".format(ratio)}:1 < 3.0 over accent-ghost", ratio >= 3.0)
        }
    }
    @Test
    fun `FAB OnAccent tint clears 4_5 over the deepened accent disc (all 12 palettes)`() {
        // Pins the IconRole.OnAccent WIRING (not just the resolver): the tint SemanticIconSpec
        // actually returns must clear 4.5:1 worst-case over the DialogActionColors-deepened
        // gradient the FAB disc fills with, for the pinned accent of every palette. Guards drift
        // where DialogActionColors stays green but the OnAccent mapping/conversion changes.
        concrete.forEach { sel ->
            val pinned = PinnedGlassEnvironment.forPinnedRoute(
                GlassEnvironment(rovaPalettes.getValue(sel), 31, false),
            ).palette
            val (ar, ag, ab) = rgb(pinned.accent)
            val (er, eg, eb) = rgb(pinned.accent2)
            val cta = DialogActionColors.resolve(intArrayOf(ar, ag, ab), intArrayOf(er, eg, eb))
            val tint = SemanticIconSpec.tint(pinned, IconRole.OnAccent)
            val mid = intArrayOf((cta.start[0] + cta.end[0]) / 2, (cta.start[1] + cta.end[1]) / 2, (cta.start[2] + cta.end[2]) / 2)
            val worst = listOf(cta.start, mid, cta.end).minOf { s -> ratioOver(tint, intArrayOf(s[0], s[1], s[2])) }
            assertTrue("$sel OnAccent ${"%.2f".format(worst)}:1 < 4.5 over FAB disc", worst >= 4.5)
        }
    }
}
