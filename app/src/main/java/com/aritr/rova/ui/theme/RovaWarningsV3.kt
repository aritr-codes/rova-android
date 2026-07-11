package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Phase 4 — v3 chrome canon for the warning surface (sheets, banners, snooze-chip,
 * recovery cards). Additive to [RovaWarnings] — the four severity colours
 * (`hard / soft / advisory / escalating`) are inherited from `RovaWarnings`; this
 * object only carries geometry + alpha + brush helpers.
 *
 * **Design authority:** `docs/design/warnings-recovery.html` v1.0 (DESIGN FROZEN
 * 2026-07-09). Compose transcribes it and never diverges from it; a visual or
 * interaction change is made in the HTML and re-approved first. ADR-0013's
 * amendment (2026-07-10) retired the earlier mockup `07c-warnings.html` as an
 * authority and superseded this object's v3 chrome values.
 *
 * Historical: `docs/superpowers/specs/2026-05-23-phase-4-warning-reskin-v3-design.md`.
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
    // WCAG 2.2 AA SC 1.4.11 (ADR-0020, TOK-03): 0.13α left the severity-chip
    // fill below 3:1 against the sheet; 0.20α gives the badge a perceivable
    // boundary. Foreground label/dot already carry severity (no 1.4.1 reliance
    // on colour alone).
    val sevChipFillAlpha = 0.20f
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
    // Pre-freeze fill alpha (Color.Black @ .55, 3.61:1 — failed AA). Its last
    // consumer migrated to pinSurface @ pinContainerAlpha in M4; the dead token
    // is retired in M11's final dead-token audit (kept here to avoid mixing an
    // unrelated test edit into the M4 surface transcription).
    val snoozeChipFillAlpha = 0.55f
    val snoozeChipBorderAlpha = 0.25f
    val snoozeChipDotPulseAlpha = 0.6f
    // Geometry — frozen spec `.snooze .pill` (warnings-recovery.html :326–:327):
    // fixed 34px visual pill, padding 0 s4 (16px), gap s2 (8px). Heights are
    // min-heights (P5) so 200% text scale cannot clip; the 48dp hit target is an
    // invisible expansion (`minimumInteractiveComponentSize`), never a taller pill.
    val snoozeChipPillHeight = 34.dp
    val snoozeChipPaddingH = 16.dp
    val snoozeChipGap = 8.dp

    // ══════════════════════════════════════════════════════════════════
    // Trust System V1 token foundation (ADR-0013 amendment 2026-07-10).
    // Transcribed from the frozen spec `docs/design/warnings-recovery.html`
    // §02 token registry. ADDITIVE: nothing below has a production consumer
    // yet — M4–M8 migrate the call sites. Values are pinned by
    // `RovaWarningsV3Test`; do not "tidy" them.
    // ══════════════════════════════════════════════════════════════════

    // ── Family 3 · locked pinned / over-media (never themed) ─────────

    /** The one pinned container surface. HTML :79 — unifies the banner's and the recovery card's raw hexes. */
    val pinSurface = Color(0xFF0B0D14)

    /**
     * The one alpha for every pinned container floating OVER MEDIA — banner
     * and snooze chip. HTML :79–:84: the banner shipped .88 (passed AA by
     * 0.004) and the chip shipped `Color.Black @ .55` (failed outright,
     * 3.61:1). The *sheet* is opaque: it is modal and covers the viewfinder,
     * so it composites against nothing. See APPX-B.
     */
    const val pinContainerAlpha = 0.94f

    /** Over-media title ink. HTML :85 — the banner title was .88; disclosed bump. */
    val mediaInk = Color.White.copy(alpha = 0.94f)

    /** Over-media dim ink (metadata). HTML :86. */
    val mediaInkDim = Color.White.copy(alpha = 0.48f)

    /** Over-media body ink. HTML :87. */
    val mediaInkBody = Color.White.copy(alpha = 0.55f)

    /** Top hairline on a pinned container. HTML :89. */
    val mediaEdgeTop = Color.White.copy(alpha = 0.12f)

    // ── Family 2 · locked severity ───────────────────────────────────

    /**
     * The near-black label on a solid severity fill. NOT debt: white-on-severity
     * reads 1.67–3.76:1 and fails AA, while this ink clears 4.5:1 on all four
     * fills (pinned as a contract in `RovaWarningsV3Test`). HTML :70–:74 —
     * graduated from a call-site literal to a named locked token, zero visual
     * delta. Never routed through `DialogActionColors` (APPX-C: that resolver
     * owns accent fills only, never the locked severity fills).
     */
    val severityCtaInk = Color(0xFF1A1A1A)

    // ── Family 1 · identity · DERIVED surfaceHi ──────────────────────

    /** The white fraction mixed into `surfaceBase` to derive [surfaceHi]. HTML :35 / :1340. */
    const val surfaceHiMixFraction = 0.08f

    /**
     * The elevated themed container behind the recovery card — `mix(white 8%,
     * surfaceBase)`. **DERIVED, spec-introduced: [RovaPalette] has no
     * `surfaceHi` member** (HTML :35–:38, :1146, :1383). It exists because the
     * recovery card stops being a pinned near-black island and adopts an
     * elevated themed surface.
     *
     * Daylight — the only light palette — carries an explicit `#FFFFFF` rather
     * than the mix (HTML :1190, `p.surfaceHi ?? mixc(...)`): white *is* the
     * elevation on a light ground, and an 8% white mix of `#F4F1EA` would be
     * indistinguishable from the base.
     *
     * The mix runs in 8-bit sRGB with the same rounding as the spec's
     * `rgbToHex(mixc(...))`, so the Kotlin result is byte-identical to the
     * frozen HTML's — e.g. Aurora `#141622` → `#272934`.
     */
    fun surfaceHi(palette: RovaPalette): Color {
        if (palette.isLight) return Color.White
        val base = palette.surfaceBase
        val mixed = ContrastMath.compositeAlphaOver(
            255, 255, 255,
            surfaceHiMixFraction.toDouble(),
            (base.red * 255f).roundToInt(),
            (base.green * 255f).roundToInt(),
            (base.blue * 255f).roundToInt(),
        )
        return Color(mixed[0], mixed[1], mixed[2])
    }

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
