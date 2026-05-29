package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Phase — settings-sheet re-skin. Mockup-exact pixel constants for the custom
 * settings sheet (`mockups/new_uiux/02-settings-sheet.html`). Settings-sheet-
 * scoped on purpose (same rationale as [RecordChromeTokens]). CSS px → `Dp`,
 * CSS `rgba` → `Color`. The CSS `backdrop-filter` blurs are NOT tokenised —
 * Compose has no backdrop-blur API; the panel fill is near-opaque (`0.97`)
 * so the no-blur approximation is faithful here.
 */
object SettingsSheetTokens {

    // ── Camera peek (top of the sheet) ──────────────────────────────────
    /** `.camera-peek` height. */
    val peekHeight = 212.dp
    /** `.peek-scrim` gradient — `rgba(0,0,0,0.18)` (top) → `rgba(0,0,0,0.62)` (bottom). */
    val peekScrimTop = Color.Black.copy(alpha = 0.18f)
    val peekScrimBottom = Color.Black.copy(alpha = 0.62f)
    /** `.peek-status` pill. */
    val peekStatusInsetStart = 16.dp
    val peekStatusInsetBottom = 14.dp
    val peekStatusPaddingH = 10.dp
    val peekStatusPaddingV = 5.dp
    val peekStatusFill = Color.Black.copy(alpha = 0.38f)
    val peekStatusStroke = Color.White.copy(alpha = 0.07f)
    val peekStatusRadius = 20.dp
    /** `.peek-dot`. */
    val peekDotSize = 5.dp
    val peekDotIdle = Color.White.copy(alpha = 0.22f)
    /** `.peek-txt` colour. */
    val peekStatusText = Color.White.copy(alpha = 0.5f)
    /** `.cam-controls` end inset (`right: 10px`). Top inset = the status-bar inset. */
    val camControlsInsetEnd = 10.dp

    // ── Sheet panel ─────────────────────────────────────────────────────
    /** `.settings-sheet` background — `rgba(9,13,20,0.97)`. */
    val sheetFill = Color(0xF7090D14)
    /** `.settings-sheet` top border — `rgba(255,255,255,0.085)`. */
    val sheetTopStroke = Color.White.copy(alpha = 0.085f)
    val sheetCornerRadius = 24.dp
    val sheetPaddingH = 18.dp
    val sheetPaddingBottom = 26.dp

    // ── Sheet-top handle ────────────────────────────────────────────────
    val handleWidth = 32.dp
    val handleHeight = 4.dp
    val handleColor = Color.White.copy(alpha = 0.16f)
    val handleRadius = 2.dp
    val sheetTopPaddingTop = 12.dp
    val sheetTopPaddingBottom = 14.dp

    // ── Section labels ──────────────────────────────────────────────────
    /** `.sheet-section-label` colour — mockup 0.20 → 0.55 for AA (SC 1.4.3,
     *  ADR-0020; opaque sheet fill, so AA is guaranteed). See TokenContrastTest. */
    val sectionLabelColor = Color.White.copy(alpha = 0.55f)
    /** Gap between a section label and the content below it. */
    val sectionLabelGap = 8.dp

    // ── Mode tabs ───────────────────────────────────────────────────────
    val modeTabsTrackFill = Color.White.copy(alpha = 0.05f)
    val modeTabsRadius = 13.dp
    val modeTabsPadding = 3.dp
    val modeTabsGap = 2.dp
    val modeTabsBottomMargin = 20.dp
    val modeTabPaddingH = 4.dp
    val modeTabPaddingV = 8.dp
    val modeTabRadius = 10.dp
    val modeTabActiveFill = Color.White.copy(alpha = 0.11f)
    val modeTabActiveText = Color.White.copy(alpha = 0.90f)
    /** Idle (selectable) tab — mockup 0.26 → 0.55 for AA (SC 1.4.3). */
    val modeTabIdleText = Color.White.copy(alpha = 0.55f)
    /** Disabled tab — 1.4.3-exempt, but mockup 0.16 was illegible; bumped to 0.40. */
    val modeTabDisabledText = Color.White.copy(alpha = 0.40f)

    // ── Setting rows ────────────────────────────────────────────────────
    /** `.s-row` vertical padding. */
    val rowPaddingV = 13.dp
    /** `.s-row` divider — `rgba(255,255,255,0.046)`. */
    val rowDivider = Color.White.copy(alpha = 0.046f)
    /** `.s-row-label` colour. */
    val rowLabelText = Color.White.copy(alpha = 0.46f)

    // ── Stepper ─────────────────────────────────────────────────────────
    val stepBtnSize = 27.dp
    val stepBtnRadius = 8.dp
    val stepBtnFill = Color.White.copy(alpha = 0.07f)
    val stepBtnStroke = Color.White.copy(alpha = 0.1f)
    val stepBtnGlyph = Color.White.copy(alpha = 0.55f)
    val stepperGap = 10.dp
    val stepValText = Color.White.copy(alpha = 0.88f)
    val stepValMinWidth = 34.dp

    // ── Quality chips ───────────────────────────────────────────────────
    val chipPaddingH = 12.dp
    val chipPaddingV = 5.dp
    val chipRadius = 20.dp
    val chipGroupGap = 5.dp
    val chipOnFill = Color.White.copy(alpha = 0.13f)
    val chipOnStroke = Color.White.copy(alpha = 0.18f)
    val chipOnText = Color.White.copy(alpha = 0.90f)
    val chipOffStroke = Color.White.copy(alpha = 0.09f)
    /** Unselected chip label — mockup 0.28 → 0.55 for AA (SC 1.4.3). */
    val chipOffText = Color.White.copy(alpha = 0.55f)

    // ── Save CTA ────────────────────────────────────────────────────────
    val ctaTopMargin = 18.dp
    val ctaPaddingV = 16.dp
    val ctaFill = Color.White.copy(alpha = 0.07f)
    val ctaStroke = Color.White.copy(alpha = 0.10f)
    val ctaRadius = 16.dp
    val ctaText = Color.White.copy(alpha = 0.72f)
}
