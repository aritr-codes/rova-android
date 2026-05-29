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
            "loopUnitText" to RecordChromeTokens.loopUnitText,
            "statusTimeText" to RecordChromeTokens.statusTimeText,
            "cellKeyText" to RecordChromeTokens.cellKeyText,
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
            "chipOffText" to SettingsSheetTokens.chipOffText,
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
    fun `disabled mode-tab text is bumped for legibility (1_4_3-exempt, at least 3 to 1)`() {
        // SC 1.4.3 exempts inactive/disabled UI components, so this is not an AA
        // gate — but the audit flagged 0.16α as illegible. Bump to a readable
        // floor without implying it is interactive AA-compliant text.
        val ratio = ratioOver(SettingsSheetTokens.modeTabDisabledText, sheetBg)
        assertTrue("disabled tab text should be >= 3:1 for legibility", ratio >= 3.0)
    }
}
