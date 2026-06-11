package com.aritr.rova.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phase 2.1A — screen-local design tokens extracted from
 * `docs/UI_DESIGN_TOKENS.md` §2.3 / §2.4 / §2.2.
 *
 * Theme-level tokens (color slots, typography slots, shape globals)
 * live in [Color], [Type], and [Theme]. Tokens here are values that
 * a *specific* layout consumes by name — putting them on
 * [androidx.compose.material3.MaterialTheme] would invite over-application
 * (e.g. nothing else in the app should pick up `recordCardBottomInset`).
 *
 * ALL-CAPS labels (`eyebrow`, `cellKey`) are upper-cased at the call
 * site via `.uppercase()`; the [TextStyle] only carries the
 * font / weight / spacing.
 */
object RovaTokens {
    // Shape — only the canonical pill is here. Card / popup / sheet shapes
    // stay as inline `RoundedCornerShape(...)` per docs/UI_DESIGN_TOKENS.md
    // §2.3 ("do not override `MaterialTheme.shapes` globally").
    val pill = RoundedCornerShape(999.dp)

    // Sizing / spacing — see docs/UI_DESIGN_TOKENS.md §2.4.
    val minHitTarget = 48.dp
    val screenEdgeMargin = 16.dp
    val recordCardBottomInset = 110.dp
    val camControlGap = 7.dp
    val camControlSize = 30.dp
    val stepperButtonSize = 27.dp
    val statusDotSize = 6.dp
    val settingsRowVerticalPadding = 13.dp
    val settingsRowDividerAlpha = 0.046f
    val primaryActionSize = 64.dp
    val stopActionSize = 72.dp

    // Typography — see docs/UI_DESIGN_TOKENS.md §2.2 ("screen-local"
    // typography tokens, separate from the M3 Typography slots).
    val eyebrow: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        letterSpacing = 2.sp
    )

    val statusPillLabel: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        fontFeatureSettings = "tnum"
    )

    val cellValue: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        fontFeatureSettings = "tnum"
    )

    val cellKey: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 8.sp,
        letterSpacing = 0.8.sp
    )

    /**
     * Landscape compact config-cell pair (rotate-spec §11 D1, 2026-06-11). The
     * landscape config column renders the SAME 5 cells at reduced density so the
     * vertical strip doesn't visually dominate the nav rail (the Phase-A NO-GO).
     * ~0.875 × the portrait cellValue/cellKey scale, per the approved
     * landscape_record_mockup.html "Slim" weight.
     */
    val cellValueCompact: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        fontFeatureSettings = "tnum"
    )

    /** Compact sibling of [cellKey] — see [cellValueCompact]. */
    val cellKeyCompact: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 7.sp,
        letterSpacing = 0.7.sp
    )

    // Phase 1 — mockup-exact record-chrome type scale (mockups/new_uiux/01-record-home.html).
    // 1 px → 1 sp. ALL-CAPS labels are .uppercase()'d at the call site.

    /** `.loop-count` — the big "4/10" numeral in the loop pill. */
    val loopCount: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        letterSpacing = (-0.6).sp,
        fontFeatureSettings = "tnum"
    )

    /** `.loop-unit` — the "LOOPS DONE" caption beside [loopCount]. */
    val loopUnit: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        letterSpacing = 1.sp
    )

    /** `.status-main` — the status-pill primary label ("Recording" / "On break"). */
    val statusMain: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.1.sp
    )

    /** `.status-time` — the status-pill trailing time ("· 0:18 left"). */
    val statusTime: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Light,
        fontSize = 11.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum"
    )

    /** `.swipe-label` — the "SWIPE TO EDIT" hint above the settings card. */
    val swipeLabel: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 8.sp,
        letterSpacing = 1.4.sp
    )

    /** `.nav-txt` — the Library / Settings / Start-Stop labels in the bottom nav. */
    val navTxt: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 9.sp,
        letterSpacing = 0.6.sp
    )

    /** `.cam-zone-tag` — the per-zone "PORTRAIT · 9:16" tag in dual mode. */
    val zoneTag: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 7.5.sp,
        letterSpacing = 1.5.sp
    )

    // ── Settings-sheet type scale (mockups/new_uiux/02-settings-sheet.html) ──

    /** `.sheet-section-label` — "RECORDING MODE" / "SETTINGS". */
    val sheetSectionLabel: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 8.5.sp,
        letterSpacing = 2.sp,
    )

    /** `.s-row-label` — the setting-row label ("Clip Duration"). */
    val sheetRowLabel: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.1.sp,
    )

    /** `.step-val` — the stepper's numeric value. */
    val sheetStepValue: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = (-0.2).sp,
        fontFeatureSettings = "tnum",
    )

    /** `.chip` — the quality chip label. */
    val sheetChip: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 0.4.sp,
    )

    /** `.mode-tab` — the recording-mode tab label. */
    val sheetModeTab: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.1.sp,
    )

    /** `.sheet-cta` — the "Save" button label. */
    val sheetCta: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.1.sp,
    )

    /** `.peek-txt` — the camera-peek mini status pill text. */
    val peekStatus: TextStyle = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        letterSpacing = 0.1.sp,
    )
}

/**
 * Phase 2.1A — warning severity tokens consumed by Phase 4
 * `WarningCenter` composables. See `docs/UI_DESIGN_TOKENS.md` §2.10
 * + `docs/WarningCenterContract.md` §3.
 *
 * Severities are **domain tokens**, not Material 3 ColorScheme slots:
 * mapping a custom severity onto a M3 slot would over-apply (e.g.
 * tinting buttons that are not warnings). [hard] re-uses the same
 * hex as `colorScheme.error`; [advisory] re-uses `colorScheme.primary`.
 * They are duplicated here intentionally so the warning render path
 * never needs to know about M3 surface roles.
 *
 * Tints are applied at **12% alpha for fills** and **85% alpha for
 * foregrounds** at the call site (mockup pattern
 * `rgba(R,G,B,0.12)` / `rgba(R,G,B,0.85)`).
 */
object RovaWarnings {
    val hard: Color = Color(0xFFEF4444)
    val soft: Color = Color(0xFFFBBF24)
    val advisory: Color = Color(0xFF5B7FFF)
    val escalating: Color = Color(0xFFF97316)
}
