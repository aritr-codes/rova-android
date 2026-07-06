package com.aritr.rova.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.2 AA (SC 1.4.3) regression guard for the white text tokens in
 * [RecordChromeTokens] and [SettingsSheetTokens] (audit findings TOK-01 /
 * TOK-02 + REC-01..07, NAV-01/02, SET-05/09; ADR-0020). Reads the *actual*
 * token alphas via [ContrastMath] so lowering any token back below the bar
 * re-breaks this test.
 *
 * **This is the authoritative SC 1.4.3 enforcement for the design tokens.**
 * ADR-0020 originally sketched a `checkA11yNoLowAlphaTextToken` static gate
 * (a regex `alpha < 0.55f` heuristic); it was rejected — contrast is
 * background-dependent (e.g. `rowLabelText` 0.46α reads 4.67:1 over the opaque
 * sheet, but the same alpha fails over bright camera footage), so a flat alpha
 * floor would false-fail AA-passing tokens. This test computes the real ratio
 * over each token's actual surface, which a regex cannot, so it covers **every**
 * text/label token — any future text token MUST be added here.
 *
 * **Backgrounds.**
 * - Settings-sheet tokens sit on the near-opaque sheet fill (`#F7090D14` ≈
 *   `#090D14`), so 4.5:1 is a genuine guarantee there.
 * - Record-chrome tokens sit on dark glass pills / the dock gradient over the
 *   *live camera preview*, which is scene-variable. Alpha alone cannot
 *   guarantee AA over arbitrarily bright footage — that needs an opaque scrim
 *   behind the text (tracked as a separate remediation). These assertions use
 *   the documented **dark-scene reference** (`MidnightSurface` #0E1216): the
 *   common indoor/night case the dark-pill chrome is designed for. The bumps
 *   strictly improve contrast on every scene.
 */
class TokenContrastTest {

    private val sheetBg = intArrayOf(0x09, 0x0D, 0x14) // SettingsSheetTokens.sheetFill, opaque part
    private val chromeDarkRef = intArrayOf(0x0E, 0x12, 0x16) // MidnightSurface — dark-scene reference

    private fun ratioOver(token: androidx.compose.ui.graphics.Color, bg: IntArray): Double =
        ContrastMath.contrastRatioForAlpha(255, 255, 255, token.alpha.toDouble(), bg[0], bg[1], bg[2])

    @Test
    fun `record chrome text tokens meet AA over the dark-scene reference`() {
        val tokens = mapOf(
            "loopCountText" to RecordChromeTokens.loopCountText,
            "loopUnitText" to RecordChromeTokens.loopUnitText,
            "statusMainText" to RecordChromeTokens.statusMainText,
            "statusTimeText" to RecordChromeTokens.statusTimeText,
            "cellValueText" to RecordChromeTokens.cellValueText,
            "cellKeyText" to RecordChromeTokens.cellKeyText,
            "cellValueReadOnlyText" to RecordChromeTokens.cellValueReadOnlyText,
            "settingsArrow" to RecordChromeTokens.settingsArrow,
            "swipeHint" to RecordChromeTokens.swipeHint,
            "navIcon" to RecordChromeTokens.navIcon,
            "navText" to RecordChromeTokens.navText,
            "zoneTagText" to RecordChromeTokens.zoneTagText,
        )
        tokens.forEach { (name, color) ->
            val ratio = ratioOver(color, chromeDarkRef)
            assertTrue(
                "$name must meet 4.5:1 over the dark-scene reference (was ${"%.2f".format(ratio)}:1)",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun `settings-sheet active text tokens meet AA over the opaque sheet`() {
        val tokens = mapOf(
            "sectionLabelColor" to SettingsSheetTokens.sectionLabelColor,
            "modeTabIdleText" to SettingsSheetTokens.modeTabIdleText,
            "modeTabActiveText" to SettingsSheetTokens.modeTabActiveText,
            "rowLabelText" to SettingsSheetTokens.rowLabelText,
            "stepValText" to SettingsSheetTokens.stepValText,
            "chipOffText" to SettingsSheetTokens.chipOffText,
            "chipOnText" to SettingsSheetTokens.chipOnText,
            "summaryText" to SettingsSheetTokens.summaryText,
            "summaryStrong" to SettingsSheetTokens.summaryStrong,
            "ctaText" to SettingsSheetTokens.ctaText,
        )
        tokens.forEach { (name, color) ->
            val ratio = ratioOver(color, sheetBg)
            assertTrue(
                "$name must meet 4.5:1 over the sheet fill (was ${"%.2f".format(ratio)}:1)",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun `camera-peek status text meets AA over the dark-scene reference`() {
        // peekStatusText sits on the peek scrim over the *live camera* (not the
        // opaque sheet), so it shares the record-chrome scene-variable caveat:
        // asserted over the dark-scene reference, the common case it's designed
        // for. Same separate-scrim remediation note as the record-chrome text.
        val ratio = ratioOver(SettingsSheetTokens.peekStatusText, chromeDarkRef)
        assertTrue(
            "peekStatusText must meet 4.5:1 over the dark-scene reference (was ${"%.2f".format(ratio)}:1)",
            ratio >= 4.5,
        )
    }

    @Test
    fun `disabled mode-tab text is bumped for legibility (1_4_3-exempt, at least 3 to 1)`() {
        // SC 1.4.3 exempts inactive/disabled UI components, so this is not an AA
        // gate — but the audit flagged 0.16α as illegible. Bump to a readable
        // floor without implying it is interactive AA-compliant text.
        val ratio = ratioOver(SettingsSheetTokens.modeTabDisabledText, sheetBg)
        assertTrue("disabled tab text should be >= 3:1 for legibility", ratio >= 3.0)
    }

    @Test
    fun `media-progress hairline meets 3 to 1 over the bottom-scrim composite in every palette`() {
        // v3.3 Library playback-progress hairline (--media-progress). A 2dp non-text UI graphic,
        // so SC 1.4.11 (>=3:1), asserted against its real backing: the bento tile's bottom scrim
        // stop (seam #060308 @ 0.58) composited over the dark-scene reference — the same
        // scene-variable caveat as the record-chrome tokens. Frozen spec v3.3 names this exact
        // contract as WHY mediaProgress is a distinct semantic role and not accentFill reuse.
        val seam = intArrayOf(0x06, 0x03, 0x08)
        val a = 0.58
        val bg = IntArray(3) { (a * seam[it] + (1 - a) * chromeDarkRef[it]).toInt() }
        val bgLum = ContrastMath.relativeLuminance(bg[0], bg[1], bg[2])
        com.aritr.rova.ui.theme.rovaPalettes.forEach { (theme, palette) ->
            val fill = com.aritr.rova.ui.library.LibraryColorSpec.mediaProgress(palette)
            val lum = ContrastMath.relativeLuminance(
                (fill.red * 255).toInt(), (fill.green * 255).toInt(), (fill.blue * 255).toInt(),
            )
            val ratio = ContrastMath.contrastRatio(lum, bgLum)
            assertTrue(
                "$theme mediaProgress must meet 3:1 over the bottom-scrim composite (was ${"%.2f".format(ratio)}:1)",
                ratio >= 3.0,
            )
        }
    }

    @Test
    fun `light quiet text (solid onSurfaceVariant) meets AA over the light background`() {
        // B2: on light, rovaQuietText returns SOLID onSurfaceVariant (Ink80)
        // over the light background (Sand30). Regression guard for the AA fix.
        val fg = ContrastMath.relativeLuminance(0x4C, 0x61, 0x75) // Ink80
        val bg = ContrastMath.relativeLuminance(0xF6, 0xF0, 0xE7) // Sand30
        val r = ContrastMath.contrastRatio(fg, bg)
        assertTrue("light quiet text must meet 4.5:1 (was ${"%.2f".format(r)}:1)", r >= 4.5)
    }

    @Test
    fun `light primary value text meets AA over the light background`() {
        // SettingsRow value text = primary (Harbor40) at 0.85α over Sand30.
        val r = ContrastMath.contrastRatioForAlpha(0x29, 0x51, 0x6E, 0.85, 0xF6, 0xF0, 0xE7)
        assertTrue("light value text must meet 4.5:1 (was ${"%.2f".format(r)}:1)", r >= 4.5)
    }
}
