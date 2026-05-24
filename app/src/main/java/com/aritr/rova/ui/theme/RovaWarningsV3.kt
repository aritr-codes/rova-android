package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phase 4 — v3 chrome canon for the warning surface (sheets, banners, snooze-chip,
 * recovery cards). Additive to [RovaWarnings] — the four severity colours
 * (`hard / soft / advisory / escalating`) are inherited from `RovaWarnings`; this
 * object only carries geometry + alpha + brush helpers.
 *
 * Authoritative mockup: `mockups/new_uiux/07c-warnings.html`.
 * Spec: `docs/superpowers/specs/2026-05-23-phase-4-warning-reskin-v3-design.md`.
 * ADR-0013 documents the canon.
 */
object RovaWarningsV3 {

    // ── Sheet ─────────────────────────────────────────────────────────
    val sheetCornerRadius = 26.dp
    val sheetHandleWidth = 32.dp
    val sheetHandleHeight = 3.dp
    val sheetHandleAlpha = 0.18f
    val sheetIconSize = 56.dp
    val sheetIconCornerRadius = 18.dp
    val sheetIconInnerStrokeAlpha = 0.22f
    val sheetIconGlowInset = (-22).dp
    val sheetIconGlowBlur = 22.dp
    val sheetIconGlowAlpha = 0.70f
    val sheetTitleSize = 15.sp
    val sheetBodySize = 11.sp
    val sheetCtaHeight = 46.dp
    val sheetCtaCornerRadius = 14.dp
    val sheetSidePadding = 20.dp
    val sheetBottomPadding = 24.dp

    // ── Severity chip (inline, above title) ──────────────────────────
    val sevChipPaddingH = 10.dp
    val sevChipPaddingV = 4.dp
    val sevChipDotSize = 5.dp
    val sevChipFillAlpha = 0.13f
    val sevChipForegroundAlpha = 0.95f

    // ── Overflow ⋯ menu ──────────────────────────────────────────────
    val overflowButtonSize = 28.dp
    val overflowTopInset = 14.dp
    val overflowRightInset = 18.dp

    // ── "Why this matters" expander ──────────────────────────────────
    val whyRowHeight = 36.dp
    val whyRowCornerRadius = 11.dp
    val whyRowBorderAlpha = 0.20f
    val whyRowForegroundAlpha = 0.78f

    // ── Banner ────────────────────────────────────────────────────────
    val bannerCornerRadius = 18.dp
    val bannerSidePadding = 12.dp
    val bannerVerticalPadding = 11.dp
    val bannerIconSize = 36.dp
    val bannerIconCornerRadius = 10.dp
    val bannerCountdownRingSize = 38.dp
    val bannerCountdownRingStroke = 3.dp
    val bannerCountdownTrackAlpha = 0.06f

    // ── CTA contrasts (a11y) ─────────────────────────────────────────
    val secondaryCtaTextAlpha = 0.68f      // R2 was 0.55 — bumped for a11y
    val secondaryCtaFillAlpha = 0.07f
    val secondaryCtaStrokeAlpha = 0.05f

    // ── Recovery card ────────────────────────────────────────────────
    val recoveryCardCornerRadius = 20.dp
    val recoveryCardGlowHeight = 60.dp
    val recoveryCardGlowBlur = 28.dp
    val recoveryCardGlowAlpha = 0.50f
    val recoveryProgressCellHeight = 7.dp
    val recoveryProgressCellGap = 4.dp
    val recoveryProgressCellRadius = 3.5.dp
    val recoveryNumericChipMinWidth = 36.dp

    // ── Snooze chip ──────────────────────────────────────────────────
    val snoozeChipRadius = 999.dp
    val snoozeChipFillAlpha = 0.55f
    val snoozeChipBorderAlpha = 0.25f
    val snoozeChipDotPulseAlpha = 0.6f

    /**
     * Radial glow brush behind the sheet icon. Severity-tinted, ~0.46 effective alpha
     * at center fading to transparent at [radiusPx]. The radius is taken in pixels —
     * callers in a `@Composable` scope compute it via
     * `with(LocalDensity.current) { RovaWarningsV3.sheetIconSize.toPx() * 0.9f }`
     * (or similar). Defaulting `radius` to `Float.POSITIVE_INFINITY` would collapse
     * the gradient to a flat fill, defeating the bloom.
     */
    fun iconGlow(severityColor: Color, radiusPx: Float): Brush = Brush.radialGradient(
        colors = listOf(
            severityColor.copy(alpha = sheetIconGlowAlpha * 0.65f),
            Color.Transparent,
        ),
        radius = radiusPx,
    )

    /** Vertical glow brush along the top edge of the recovery card. */
    fun recoveryGlow(severityColor: Color): Brush = Brush.verticalGradient(
        colors = listOf(
            severityColor.copy(alpha = recoveryCardGlowAlpha),
            Color.Transparent,
        ),
    )
}
