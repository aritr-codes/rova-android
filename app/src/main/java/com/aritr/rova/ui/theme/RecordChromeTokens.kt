package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phase 1 — mockup-exact pixel constants for the record-screen re-skin
 * (`mockups/new_uiux/01-record-home.html`). Record-screen-scoped on purpose:
 * a record-only object cannot over-apply to unrelated UI (same rationale as
 * [RovaTokens]' KDoc). Phase 2/3 composables consume these; Phase 1 only
 * declares them.
 *
 * Colour tokens named `*Text` are white at the mockup's `rgba` alpha — they
 * are text-fill colours, applied directly as `color = ...`. Geometry tokens
 * are `Dp`. CSS `backdrop-filter` blur is deliberately NOT tokenised — Compose
 * has no backdrop-blur API; the semi-transparent fills are the approximation.
 */
object RecordChromeTokens {

    // ── Surface fills & strokes ──────────────────────────────────────────
    /** `.status-pill` / `.loop-pill` background — `rgba(0,0,0,0.40)`. */
    val glassFill = Color.Black.copy(alpha = 0.40f)
    /** `.status-pill` / `.loop-pill` border — `rgba(255,255,255,0.07)`. */
    val glassStroke = Color.White.copy(alpha = 0.07f)
    /** `.cam-ctrl-btn` background — `rgba(0,0,0,0.38)`. */
    val camControlFill = Color.Black.copy(alpha = 0.38f)
    /** `.cam-ctrl-btn` border — `rgba(255,255,255,0.09)`. */
    val camControlStroke = Color.White.copy(alpha = 0.09f)
    /**
     * `.settings-card` background. The mockup uses `rgba(255,255,255,0.065)`
     * **with** `backdrop-filter: blur(24px)` — a 6.5%-white fill only reads as
     * a panel because the blur darkens what shows through. Compose has no
     * backdrop-blur, so over bright camera footage that fill is invisible.
     * Substituted with the same dark fill the pills use (`rgba(0,0,0,0.40)`),
     * which reads correctly on-device — the deliberate no-blur approximation.
     */
    val settingsCardFill = Color.Black.copy(alpha = 0.40f)
    /** `.settings-card` border — `rgba(255,255,255,0.09)`. */
    val settingsCardStroke = Color.White.copy(alpha = 0.09f)
    /** `.s-cell + .s-cell` divider — `rgba(255,255,255,0.07)`. */
    val cellDivider = Color.White.copy(alpha = 0.07f)
    /**
     * Slice B — dock fill is now a vertical gradient brush. The top
     * 35% is fully transparent so the camera preview reads continuously
     * through the dock zone; the gradient ramps to 0.55 black at the
     * bottom to provide readable contrast behind Library/FAB/Settings.
     * Paint on an outer Box that extends through `windowInsetsPadding`
     * so the brush dissolves into the OS-transparent gesture-nav
     * region (Slice A) with no band edge. See
     * `docs/superpowers/specs/2026-05-23-edge-to-edge-record-home-slice-b-design.md`
     * §3.1.
     */
    val bottomNavBrush: Brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0.00f to Color.Transparent,
            0.35f to Color.Transparent,
            0.55f to Color.Black.copy(alpha = 0.20f),
            0.80f to Color.Black.copy(alpha = 0.45f),
            1.00f to Color.Black.copy(alpha = 0.55f),
        )
    )

    /** Slice B — Mode tap-cycle chip background. */
    val modeChipFill = Color.White.copy(alpha = 0.07f)
    /** Slice B — Mode tap-cycle chip stroke. */
    val modeChipStroke = Color.White.copy(alpha = 0.10f)
    /** Slice B — Mode tap-cycle chip's `↻` glyph alpha when enabled. */
    val modeChipGlyphAlphaEnabled = 0.35f
    /** Slice B — Mode tap-cycle chip's `↻` glyph alpha when dimmed. */
    val modeChipGlyphAlphaDisabled = 0.12f

    // ── Status dots ──────────────────────────────────────────────────────
    /** `.dot-idle` — `rgba(255,255,255,0.25)`. */
    val dotIdle = Color.White.copy(alpha = 0.25f)
    /** `.dot-recording` — `#ef4444`. */
    val dotRecording = Color(0xFFEF4444)
    /** `.dot-break` — `#94a3b8` (slate; corrects today's amber). */
    val dotBreak = Color(0xFF94A3B8)

    // ── FAB (`.center-btn`) ──────────────────────────────────────────────
    /** `.btn-start` background — `rgba(255,255,255,0.07)`. */
    val fabStartFill = Color.White.copy(alpha = 0.07f)
    /** `.btn-start` border — `rgba(255,255,255,0.15)`. */
    val fabStartStroke = Color.White.copy(alpha = 0.15f)
    /** `.btn-stop` background — `rgba(239,68,68,0.12)`. */
    val fabStopFill = Color(0xFFEF4444).copy(alpha = 0.12f)
    /** `.btn-stop` border — `rgba(239,68,68,0.30)`. */
    val fabStopStroke = Color(0xFFEF4444).copy(alpha = 0.30f)
    /** `.btn-stop::after` outer ring — `rgba(239,68,68,0.10)`. */
    val fabStopRing = Color(0xFFEF4444).copy(alpha = 0.10f)
    /** `.stop-sq` — `#ef4444`. */
    val stopSquare = Color(0xFFEF4444)

    // ── Camera-zone framing (dual mode) ──────────────────────────────────
    /** `.cam-split-divider` — `rgba(255,255,255,0.14)`. */
    val splitDivider = Color.White.copy(alpha = 0.14f)
    /** `.camera-grid` line — `rgba(255,255,255,0.018)`. */
    val cameraGridLine = Color.White.copy(alpha = 0.018f)
    /** `.focus-frame` bracket — `rgba(255,255,255,0.8)` × `opacity:0.25` = 0.20. */
    val focusFrameStroke = Color.White.copy(alpha = 0.20f)

    // ── Text-fill colours (white at the mockup alpha) ────────────────────
    /** `.loop-count` — `rgba(255,255,255,0.93)`. */
    val loopCountText = Color.White.copy(alpha = 0.93f)
    /** `.loop-unit` — `rgba(255,255,255,0.32)`. */
    val loopUnitText = Color.White.copy(alpha = 0.32f)
    /** `.status-main` — `rgba(255,255,255,0.65)`. */
    val statusMainText = Color.White.copy(alpha = 0.65f)
    /** `.status-time` — `rgba(255,255,255,0.32)`. */
    val statusTimeText = Color.White.copy(alpha = 0.32f)
    /** `.s-val` — `rgba(255,255,255,0.88)`. */
    val cellValueText = Color.White.copy(alpha = 0.88f)
    /** `.s-key` — `rgba(255,255,255,0.28)`. */
    val cellKeyText = Color.White.copy(alpha = 0.28f)
    /** Read-only Mode cell value — existing 0.50 alpha, kept. */
    val cellValueReadOnlyText = Color.White.copy(alpha = 0.50f)
    /** `.settings-arrow` — `rgba(255,255,255,0.18)`. */
    val settingsArrow = Color.White.copy(alpha = 0.18f)
    /** `.swipe-hint` container opacity — `0.22`. */
    val swipeHint = Color.White.copy(alpha = 0.22f)
    /** `.nav-ico` glyph — `rgba(255,255,255,0.35)`. */
    val navIcon = Color.White.copy(alpha = 0.35f)
    /** `.nav-txt` — `rgba(255,255,255,0.30)`. */
    val navText = Color.White.copy(alpha = 0.30f)
    /** `.cam-zone-tag` — `rgba(255,255,255,0.32)`. */
    val zoneTagText = Color.White.copy(alpha = 0.32f)

    // ── Pills ────────────────────────────────────────────────────────────
    /** `.status-pill` corner radius. */
    val statusPillRadius = 20.dp
    /** `.loop-pill` corner radius. */
    val loopPillRadius = 11.dp
    /** `.status-pill` padding — `6px 11px`. */
    val statusPillPaddingH = 11.dp
    val statusPillPaddingV = 6.dp
    /** `.loop-pill` padding — `8px 13px`. */
    val loopPillPaddingH = 13.dp
    val loopPillPaddingV = 8.dp
    /** `.status-pill` inner gap. */
    val pillContentGap = 7.dp
    /** `.loop-pill` inner gap. */
    val loopPillContentGap = 6.dp
    /** `.top-overlay` vertical gap between loop pill and status pill. */
    val topOverlayGap = 8.dp
    /** `.dot` diameter. */
    val dotSize = 6.dp

    // ── Camera controls ──────────────────────────────────────────────────
    /** `.cam-ctrl-btn` diameter. */
    val camControlSize = 30.dp
    /** `.cam-controls` vertical gap. */
    val camControlGap = 7.dp

    // ── Settings card ────────────────────────────────────────────────────
    /**
     * Slice B — pill corner radius for the settings card. Supersedes
     * the former `settingsCardRadius = 14.dp` (deleted in Slice B).
     */
    val settingsCardRadiusPill = 22.dp
    /** Slice B — Mode chip corner radius (matches the cell-divider visual anchor). */
    val modeChipCornerRadius = 11.dp
    /** Slice B — Mode chip `↻` glyph text size. */
    val modeChipGlyphSize = 7.sp
    /** `.settings-card` padding — `7px 12px`. */
    val settingsCardPaddingH = 12.dp
    val settingsCardPaddingV = 7.dp
    /** `.s-cell` horizontal padding. */
    val settingsCellPaddingH = 3.dp
    /** `.settings-wrap` vertical gap. */
    val settingsWrapGap = 7.dp
    /** `.settings-wrap` bottom offset. */
    val settingsCardBottomInset = 110.dp
    /** `.swipe-bar` dimensions. */
    val swipeBarWidth = 30.dp
    val swipeBarHeight = 2.dp

    // ── Bottom nav ───────────────────────────────────────────────────────
    /** `.bottom-nav` height. */
    val bottomNavHeight = 106.dp
    /** `.bottom-nav` horizontal padding. */
    val bottomNavPaddingH = 28.dp
    /** `.bottom-nav` bottom padding. */
    val bottomNavPaddingBottom = 18.dp
    /** `.nav-item` / `.center-btn-wrap` inner gap. */
    val navItemGap = 5.dp
    /** `.nav-ico` rounded container size. */
    val navIconBoxSize = 42.dp
    /** Inner `<svg>` glyph size within `.nav-ico`. */
    val navIconGlyphSize = 20.dp
    /** `.nav-ico` corner radius. */
    val navIconCornerRadius = 12.dp
    /** `.center-btn` diameter. */
    val fabSize = 56.dp
    /** `.btn-stop::after` ring inset (negative in CSS — extends outward). */
    val fabStopRingInset = 5.dp
    /** `.stop-sq` dimensions. */
    val stopSquareSize = 18.dp
    val stopSquareRadius = 4.dp

    // ── Shared geometry ──────────────────────────────────────────────────
    /** `.top-overlay` / `.cam-controls` / `.settings-wrap` edge margin. */
    val screenEdgeMargin = 16.dp
    /** `.cam-split-divider` height. */
    val splitDividerHeight = 2.dp
    /** `.cam-zone-tag` offsets. */
    val zoneTagPaddingEnd = 13.dp
    val zoneTagPaddingBottom = 9.dp
    /** `.focus-frame` size. */
    val focusFrameSize = 60.dp

    // ── Camera guides (decorative overlays — mockup 01 cam-zone) ─────────
    /** `.camera-grid` cell width — CSS `background-size` X (`105.3px`). */
    val cameraGridCellWidth = 105.3.dp
    /** `.camera-grid` cell height — CSS `background-size` Y (`228.3px`). */
    val cameraGridCellHeight = 228.3.dp
    /** `.camera-grid` line thickness — the linear-gradient's `1px` band. */
    val cameraGridLineWidth = 1.dp
    /** `.focus-frame` corner-bracket arm length — CSS corner `14px`. */
    val focusFrameCornerArm = 14.dp
    /** `.focus-frame` corner-bracket stroke width — CSS `border-width: 1.5px`. */
    val focusFrameStrokeWidth = 1.5.dp

    // ── Recording-frame guide (P+L mode — always on, ADR-0010) ─────────
    /**
     * Recording-frame outline colour — faint light-gray, low alpha. Per
     * `docs/superpowers/specs/2026-05-22-dualshot-preview-crop-fill-design.md`
     * §5.3 / §5.4. Deliberately subtle so it does not compete with the live
     * preview; pairs with [recordingFrameStrokeWidth].
     */
    val recordingFrameOutline = Color(0xFFB0B4BC).copy(alpha = 0.38f)
    /** Recording-frame outline stroke width. */
    val recordingFrameStrokeWidth = 1.dp
    /**
     * Recording-frame scrim — faint black over the non-recorded preview
     * margin. Signals "this part isn't captured".
     */
    val recordingFrameScrim = Color.Black.copy(alpha = 0.22f)
}
