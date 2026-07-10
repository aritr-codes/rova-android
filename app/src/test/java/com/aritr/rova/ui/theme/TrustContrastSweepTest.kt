package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import com.aritr.rova.ui.library.LibraryColorSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JVM analogue of the frozen spec's on-load contrast sweep — the Trust System's CI gate.
 *
 * **Authority:** `docs/design/warnings-recovery.html` `contrastRows()` (`:1432`–`:1536`).
 * §03: *"a specimen that fails cannot be frozen."* Every text and mark pair a frozen specimen
 * actually paints is asserted here: **78 pairs × 12 palettes × 3 media frames = 2808 assertions.**
 *
 * ## What this test is not
 *
 * It is **not CSS-derived**. Every colour is read from [RovaPalette], [RovaWarnings] and the M1
 * [RovaWarningsV3] tokens (see `TrustInkSites.kt`). The HTML supplies the *shape* of the matrix —
 * which pairs exist, which backing each ink sits on, which target each must clear — never the
 * numbers. A sweep fed the spec's `:root` decimals would prove the spec self-consistent and leave
 * the shipped tokens unverified.
 *
 * ## Rejected / PRE-FREEZE rows
 *
 * The spec's inspector renders the rejected alternatives (the 0.55 snooze chip at its real 0.78
 * ink, the white severity label, the fixed 62% severity mix and 72% accent mix, the raw-hue mark,
 * the alpha-dimmed body ink, `severityColor@.95`) behind a *Show pre-freeze values* toggle, and
 * excludes them from both the 78-pair count and the pass/fail verdict (`:1547`, `:1550`). This
 * port preserves that exclusion exactly: they never enter [frozenRowsFor] and are never asserted
 * to pass. Three of them are pinned below as **fixtures** — evidence that the port computes the
 * same world, and that the sweep is sensitive enough to catch a regression.
 */
class TrustContrastSweepTest {

    private data class SweepPair(val name: String, val ratio: Double, val target: Double)

    private val hard = RovaWarnings.hard

    /** The spec's own epsilon (`:1540` `r >= need - 1e-9`). */
    private val epsilon = 1e-9

    // ── the 78 frozen pairs, per (palette, frame) cell ───────────────────────

    private fun frozenRowsFor(palette: RovaPalette, frame: MediaFrame): List<SweepPair> {
        val rows = mutableListOf<SweepPair>()
        fun add(name: String, fg: Rgb, bg: Rgb, target: Double) =
            rows.add(SweepPair(name, ratioRgb(fg, bg), target))

        val pin = pinRgb()
        val capsule = capsuleRgb(frame.rgb)
        val surfHi = surfaceHiOf(palette)
        val tHi = palette.textHigh.rgb()
        val aHi = palette.textHigh.alpha.toDouble()

        val mediaInk = RovaWarningsV3.mediaInk.alpha.toDouble()
        val mediaInkDim = RovaWarningsV3.mediaInkDim.alpha.toDouble()
        val mediaInkBody = RovaWarningsV3.mediaInkBody.alpha.toDouble()
        val ghostFill = RovaWarningsV3.secondaryCtaFillAlpha.toDouble()
        val whyFg = RovaWarningsV3.whyRowForegroundAlpha.toDouble()

        // ── pinned host (sheet opaque · banner & chip over the frame) ── 7 rows
        add("Banner title", overRgb(WHITE_RGB, mediaInk, capsule), capsule, 4.5)
        add("Banner sub-text", overRgb(WHITE_RGB, mediaInkDim, capsule), capsule, 4.5)
        add("Sheet title", overRgb(WHITE_RGB, mediaInk, pin), pin, 4.5)
        add("Sheet body", overRgb(WHITE_RGB, mediaInkBody, pin), pin, 4.5)
        val ghostPin = overRgb(WHITE_RGB, ghostFill * mediaInk, pin)
        add("Ghost CTA label · sheet", overRgb(WHITE_RGB, mediaInkBody, ghostPin), ghostPin, 4.5)
        add("\"Why this matters\" label", overRgb(WHITE_RGB, whyFg * mediaInk, pin), pin, 4.5)
        add("Snooze chip label", overRgb(WHITE_RGB, mediaInk, capsule), capsule, 4.5)

        // ── themed host ── 6 rows
        val ghostRecovery = overRgb(tHi, aHi * ghostFill, surfHi)
        add("Recovery title", overRgb(tHi, aHi, surfHi), surfHi, 4.5)
        add("Recovery body", quietOver(palette, surfHi), surfHi, 4.5)
        add("Ghost CTA label · recovery", quietOver(palette, ghostRecovery), ghostRecovery, 4.5)
        // `color-mix(sev 62%, tHigh@a)` composited over bg reduces exactly to
        // mix(sev, .62, over(tHigh, a, bg)) — the alpha cancels.
        add(
            "Destructive CTA label",
            overRgb(hard.rgb(), ResolveInk.MIX_LABEL, tHighOver(palette, surfHi)),
            surfHi,
            4.5,
        )
        val failBox = overRgb(hard.rgb(), SEV_TINT_FILL_ALPHA, surfHi)
        add("Merge-failure body", quietOver(palette, failBox), failBox, 4.5)
        add(
            "Accent-as-text · vendor link",
            ResolveInk.of(palette.accent, surfHi.toColor(), ResolveInk.TARGET_TEXT, tHighOver(palette, surfHi).toColor(), ResolveInk.MIX_ACCENT).color.rgb(),
            surfHi,
            4.5,
        )

        // ── strip / settings, per severity ── 12 rows
        for (severity in Severity.entries) {
            val tint = tintOf(palette, severity.color)
            add("Strip / settings title · $severity", overRgb(tHi, aHi, tint), tint, 4.5)
            add("Strip body · $severity", quietOver(palette, tint), tint, 4.5)
            add(
                "Accent-as-text · \"Fix\" · $severity",
                ResolveInk.of(palette.accent, tint.toColor(), ResolveInk.TARGET_TEXT, tHighOver(palette, tint).toColor(), ResolveInk.MIX_ACCENT).color.rgb(),
                tint,
                4.5,
            )
        }

        // ── locked severity fills ── 4 rows
        for (severity in Severity.entries) {
            add("Severity CTA · $severity", RovaWarningsV3.severityCtaInk.rgb(), severity.color.rgb(), 4.5)
        }

        // ── every resolved mark / label site (8 sites × 4 severities) ── 32 + 16 rows
        // `site.acc` backings are already asserted above (`set` per severity, `recov` once).
        for (site in INK_SITES) {
            for (severity in Severity.entries) {
                val hue = severity.color
                val top = site.top(palette, hue, frame.rgb).toColor()
                site.dot?.let { dot ->
                    val bg = dot(palette, hue, frame.rgb)
                    val ink = ResolveInk.of(hue, bg.toColor(), ResolveInk.TARGET_MARK, top, ResolveInk.MIX_MARK)
                    add("Mark · ${site.key} · $severity", ink.color.rgb(), bg, 3.0)
                }
                site.lbl?.let { lbl ->
                    val bg = lbl(palette, hue, frame.rgb)
                    val ink = ResolveInk.of(hue, bg.toColor(), ResolveInk.TARGET_TEXT, top, ResolveInk.MIX_LABEL)
                    add("Label · ${site.key} · $severity", ink.color.rgb(), bg, 4.5)
                }
            }
        }

        // ── accent CTA, resolved from the PALETTE, not from live CSS ── 1 row
        // `LibraryColorSpec.accentCta`/`accentInk` already wrap `DialogActionColors.resolve` with
        // the degenerate (accent, accent) gradient — the spec's `ctaResolve` — and already own the
        // house `#0E1116` on-accent ink. Reused rather than re-derived (T4 / no duplicate math).
        add(
            "Accent CTA (Combine / Retry)",
            LibraryColorSpec.accentInk(palette).rgb(),
            LibraryColorSpec.accentFill(palette).rgb(),
            4.5,
        )

        return rows
    }

    // ── the matrix ───────────────────────────────────────────────────────────

    @Test fun everyFrozenPair_clearsItsTarget_onEveryPaletteAndFrame() {
        val failures = mutableListOf<String>()
        var asserted = 0

        for (palette in rovaPalettes.values) {
            for (frame in MediaFrame.entries) {
                for ((name, ratio, target) in frozenRowsFor(palette, frame)) {
                    asserted++
                    if (ratio < target - epsilon) {
                        failures += "${palette.id}/$frame · $name = %.4f:1 (needs %.1f:1)".format(ratio, target)
                    }
                }
            }
        }

        assertEquals(2808, asserted)
        assertTrue(
            "A specimen that fails cannot be frozen. ${failures.size} failing pair(s):\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test fun theSweepAssertsExactly78FrozenPairsPerCell() {
        for (palette in rovaPalettes.values) {
            for (frame in MediaFrame.entries) {
                assertEquals("${palette.id}/$frame", 78, frozenRowsFor(palette, frame).size)
            }
        }
    }

    @Test fun frozenRows_coverAllTwelvePalettesAndThreeFrames() {
        assertEquals(12, rovaPalettes.size)
        assertEquals(3, MediaFrame.entries.size)
        assertEquals(4, Severity.entries.size)
        assertEquals(8, INK_SITES.size)
    }

    /** Row names are the pair identities; none may carry a rejected / pre-freeze marker. */
    @Test fun rejectedAndPreFreezeRows_areExcludedFromTheFrozenMatrix() {
        val marker = Regex("rejected|PRE-FREEZE")
        for (palette in rovaPalettes.values) {
            for (frame in MediaFrame.entries) {
                for ((name, _, _) in frozenRowsFor(palette, frame)) {
                    assertFalse("frozen row carries a pre-freeze marker: $name", marker.containsMatchIn(name))
                }
            }
        }
    }

    // ── fixtures: the port computes the same world as the frozen spec ────────

    /**
     * PRE-FREEZE, excluded from the matrix above. The snooze chip shipped `Color.Black @ .55`
     * with a `.78` white label and reads **3.61:1** over a white frame — the spec's one outright
     * AA failure, and the reason `pinSurface @ .94` + `mediaInk` exist (M4 repairs it).
     *
     * This is the cleanest cross-check of the port: no palette alpha enters it, so the value is
     * bit-for-bit the number the HTML displays.
     */
    @Test fun preFreeze_snoozeChipLabel_reads3_61AndFailsAA() {
        val black55 = overRgb(rgbOf(0, 0, 0), 0.55, MediaFrame.WHITE.rgb)
        val ratio = ratioRgb(overRgb(WHITE_RGB, 0.78, black55), black55)

        assertEquals(3.61, ratio, 0.005)
        assertTrue("the pre-freeze snooze chip must fail AA", ratio < 4.5)
    }

    /** And its frozen replacement clears AA comfortably on the worst (white) frame. */
    @Test fun frozen_snoozeChipLabel_clearsAAOnEveryFrame() {
        for (frame in MediaFrame.entries) {
            val capsule = capsuleRgb(frame.rgb)
            val ratio = ratioRgb(overRgb(WHITE_RGB, RovaWarningsV3.mediaInk.alpha.toDouble(), capsule), capsule)
            assertTrue("snooze label on $frame = $ratio", ratio >= 4.5)
        }
    }

    /**
     * PRE-FREEZE, excluded. The rejected fixed `color-mix(sev 62%, tHigh)` severity label reads
     * **4.28:1** on Daylight's history-strip chip — below AA. [ResolveInk]'s deepen branch is what
     * repairs it. This row also proves the sweep is *sensitive*: were a surface to paint the fixed
     * mix instead of the resolved ink, `everyFrozenPair_clearsItsTarget` would fire.
     */
    @Test fun preFreeze_fixedSeverityMix_failsAAOnDaylightStripChip() {
        val daylight = rovaPalettes.getValue(ThemeSelection.DAYLIGHT)
        val tint = tintOf(daylight, hard)
        val backing = overRgb(hard.rgb(), RovaWarningsV3.sevChipFillAlpha.toDouble(), tint)

        val fixedMix = overRgb(hard.rgb(), ResolveInk.MIX_LABEL, tHighOver(daylight, tint))
        val resolved = ResolveInk.of(hard, backing.toColor(), ResolveInk.TARGET_TEXT, tHighOver(daylight, tint).toColor(), ResolveInk.MIX_LABEL)

        assertEquals(4.28, ratioRgb(fixedMix, backing), 0.005)
        assertTrue("the rejected fixed mix must fail AA", ratioRgb(fixedMix, backing) < 4.5)
        assertTrue("the resolved ink must clear AA", ratioRgb(resolved.color.rgb(), backing) >= 4.5)
    }

    /**
     * The destructive CTA label — a *deliberately* fixed mix (APPX-D exempts it: it is the one ink
     * whose backing is always `surfaceHi`). Worst on Jade, best on Eclipse, across the 11 dark
     * palettes.
     *
     * The spec's inspector displays **5.70:1 / 7.37:1**; this substrate reads 5.72 / 7.41 because
     * it reads the shipped tokens rather than the spec's CSS decimals — `textHigh` is `0xF0FFFFFF`
     * (alpha `240/255 = 0.9412`, not `0.94`) and `surfaceHi` is the materialized 8-bit token. The
     * *ordering* property is substrate-independent and is what this pins.
     */
    @Test fun destructiveCtaLabel_worstIsJade_bestIsEclipse_acrossDarkPalettes() {
        val ratios = rovaPalettes.values
            .filterNot { it.isLight }
            .associate { palette ->
                val surfHi = surfaceHiOf(palette)
                palette.id to ratioRgb(overRgb(hard.rgb(), ResolveInk.MIX_LABEL, tHighOver(palette, surfHi)), surfHi)
            }

        assertEquals(11, ratios.size)
        assertEquals(ThemeSelection.JADE, ratios.minByOrNull { it.value }!!.key)
        assertEquals(ThemeSelection.ECLIPSE, ratios.maxByOrNull { it.value }!!.key)
        assertEquals(5.72, ratios.getValue(ThemeSelection.JADE), 0.005)
        assertEquals(7.41, ratios.getValue(ThemeSelection.ECLIPSE), 0.005)
        assertTrue("every dark palette clears AA", ratios.values.all { it >= 4.5 })
    }

    /**
     * The one derived backing is the one the M1 note litigates: `surfaceHi` is the DERIVED
     * `mix(white 8%, surfaceBase)`, never the spec's dead `:root` bootstrap literal `#242737`.
     */
    @Test fun surfaceHi_isDerived_notTheBootstrapLiteral() {
        val aurora = rovaPalettes.getValue(ThemeSelection.AURORA)
        assertEquals(Color(0xFF272934), RovaWarningsV3.surfaceHi(aurora))
        assertEquals(Color.White, RovaWarningsV3.surfaceHi(rovaPalettes.getValue(ThemeSelection.DAYLIGHT)))
    }
}
