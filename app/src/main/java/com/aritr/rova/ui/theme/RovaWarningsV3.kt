package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
    // Frozen spec `.sheet` (warnings-recovery.html :268–:293). The drag-handle
    // tokens (M5-era) were deleted in M6: the frozen sheet renders NO handle on a
    // hard-block sheet (nothing drags it — `confirmValueChange` blocks Hidden) and
    // the stock `BottomSheetDefaults.DragHandle()` on a dismissible one. The
    // `sheetTitleSize`/`sheetBodySize` tokens were deleted in M6 too — the sheet
    // title/body carry the spec type scale (`.t-title` 15sp/600, `.t-body` 12.5sp)
    // as inline overrides on the Material roles, per parity plan §5.
    val sheetCornerRadius = 26.dp
    val sheetIconSize = 56.dp
    val sheetIconCornerRadius = 18.dp
    val sheetIconInnerStrokeAlpha = 0.22f
    val sheetIconGlowInset = (-22).dp
    val sheetIconGlowBlur = 22.dp
    val sheetIconGlowAlpha = 0.70f
    // Frozen spec `.cta{min-height:var(--target)}` = 48 (P5 min-height, not fixed —
    // 200% text can grow it). M6 lifted it 46→48.
    val sheetCtaHeight = 48.dp
    val sheetCtaCornerRadius = 14.dp
    val sheetSidePadding = 20.dp
    val sheetBottomPadding = 24.dp

    // ── Severity chip (inline, above title) ──────────────────────────
    // Frozen spec `.sevchip{border-radius:var(--r-sm)}` = 10 (warnings-recovery.html
    // :203, `--r-sm:10px` :97). The eyebrow chip was a full pill (snoozeChipRadius)
    // pre-M6; M6 pins it to the r-sm ladder with its first consumer.
    val sevChipRadius = 10.dp
    val sevChipPaddingH = 10.dp
    val sevChipPaddingV = 4.dp
    val sevChipDotSize = 5.dp
    // WCAG 2.2 AA SC 1.4.11 (ADR-0020, TOK-03): 0.13α left the severity-chip
    // fill below 3:1 against the sheet; 0.20α gives the badge a perceivable
    // boundary. Foreground label/dot already carry severity (no 1.4.1 reliance
    // on colour alone).
    val sevChipFillAlpha = 0.20f
    // Pre-freeze sheet-chip label alpha (severityColor @ .95). Its last consumer migrated to the
    // resolved `pinchip` label ink in M6 (`ResolveInk` LABEL); left for M11's dead-token audit to
    // avoid an unrelated test edit here, exactly as `snoozeChipFillAlpha` was in M4.
    val sevChipForegroundAlpha = 0.95f

    // ── Overflow ⋯ menu ──────────────────────────────────────────────
    val overflowButtonSize = 28.dp
    val overflowTopInset = 14.dp
    val overflowRightInset = 18.dp

    // ── "Why this matters" expander ──────────────────────────────────
    // Frozen spec `.whyrow` (warnings-recovery.html :285–:287): r-sm 10 (M6 lifted
    // 11→10), min-height 36, border `color-mix(ink-high 20%)`, foreground
    // `color-mix(ink-high 78%)`. In the pinned host `--ink-high == --media-ink`
    // (:383), so the row is NEUTRAL (white-based `mediaInk`), not severity-tinted —
    // M6 retranscribed it off `RovaWarnings.advisory`.
    val whyRowHeight = 36.dp
    val whyRowCornerRadius = 10.dp
    val whyRowBorderAlpha = 0.20f
    val whyRowForegroundAlpha = 0.78f

    // ── Banner ────────────────────────────────────────────────────────
    // Frozen spec `.banner` (warnings-recovery.html :295–:305): r-lg 18 container,
    // padding var(--s3) = 12 both axes (vertical was off-ladder at 11, APPX-A
    // :897), gap var(--s3) = 12, r-sm 10 icon box. The CountdownRing tokens were
    // deleted in M5 (Q1: it depicted a countdown the system never ran; APPX-F :1054).
    val bannerCornerRadius = 18.dp
    val bannerSidePadding = 12.dp
    val bannerVerticalPadding = 12.dp
    val bannerGap = 12.dp
    val bannerIconSize = 36.dp
    val bannerIconCornerRadius = 10.dp

    // ── CTA contrasts (a11y) ─────────────────────────────────────────
    // Frozen ghost CTA `.cta-ghost` (warnings-recovery.html :244–:246): fill
    // `color-mix(ink-high 7%)`, border `color-mix(ink-high 12%)`, label `--ink-body`
    // (= `mediaInkBody` in the pinned host). M6 lifted the border .05→.12 and moved
    // the ghost label onto `mediaInkBody`; `secondaryCtaTextAlpha` (.68, an older
    // a11y bump for a lighter backing) is thereby superseded and left for M11's
    // dead-token audit — the near-black pin backing clears AA at ink-body .55, proven
    // by `TrustContrastSweepTest` ("Ghost CTA label · sheet").
    val secondaryCtaTextAlpha = 0.68f
    val secondaryCtaFillAlpha = 0.07f
    val secondaryCtaStrokeAlpha = 0.12f

    // ── Recovery card ────────────────────────────────────────────────
    // Frozen spec `.recov{border-radius:var(--r-lg)}` = 18 (warnings-recovery.html
    // :355, `--r-lg:18px` :99 — "was 20px, the app's one outlier"). M8 lifts it
    // 20→18 with its first frozen consumer.
    val recoveryCardCornerRadius = 18.dp
    val recoveryCardGlowHeight = 60.dp
    val recoveryCardGlowBlur = 28.dp
    val recoveryCardGlowAlpha = 0.50f
    val recoveryProgressCellHeight = 7.dp
    val recoveryProgressCellGap = 4.dp
    val recoveryProgressCellRadius = 3.5.dp
    val recoveryNumericChipMinWidth = 36.dp
    // Merge-failure failbox (frozen `.failbox` :375–:377): r-sm 10 (`--r-sm:10px` :97),
    // fill `color-mix(sev-hard 8%)`, border `hair (1dp) color-mix(sev-hard 22%)`. M9's
    // first consumer. The two alphas are inlined at the RecoveryCard call site (severity-
    // derived, like the `cta-dest` .30 border), leaving only the geometry pinned here.
    val failBoxCornerRadius = 10.dp

    // ── Snooze chip ──────────────────────────────────────────────────
    val snoozeChipRadius = 999.dp
    // Pre-freeze fill alpha (Color.Black @ .55, 3.61:1 — failed AA). Its last
    // consumer migrated to pinSurface @ pinContainerAlpha in M4; the dead token
    // is retired in M11's final dead-token audit (kept here to avoid mixing an
    // unrelated test edit into the M4 surface transcription).
    val snoozeChipFillAlpha = 0.55f
    val snoozeChipBorderAlpha = 0.25f
    // Dot pulse motion — frozen spec `@keyframes pulse` (warnings-recovery.html
    // :336–:337): `pulse 1.6s var(--ease-std)` with `50%{opacity:.45}`. The min
    // opacity is .45 and the full period is 1600ms, eased with
    // `RovaMotion.easeStandard`. M4 shipped .60 / 1.5s tween as tracked debt; M5
    // retranscribes it exactly (parity plan M4 As-shipped ⚠️ note).
    val snoozeChipDotPulseAlpha = 0.45f
    val snoozeChipPulsePeriodMs = 1600
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

    /**
     * M10 (frozen spec §11 `reduceTransparency` :853): a pinned container floating
     * over the live viewfinder renders at [pinContainerAlpha] (.94) normally, but
     * **fully opaque** (1f) when the OS reduce-transparency signal is active —
     * "over-media substrates render fully opaque." Reads the existing
     * [com.aritr.rova.ui.components.ReducedTransparency] seam at the call sites
     * ([com.aritr.rova.ui.warnings.WarningTopBannerV3] + `WarningSnoozeChip`); this
     * is the pure pick so it is unit-testable without a Compose UI test. The modal
     * sheet is already opaque and does not read this. Default (signal off) is
     * byte-identical to the pre-M10 [pinContainerAlpha] render.
     */
    fun pinContainerAlphaFor(reduceTransparency: Boolean): Float =
        if (reduceTransparency) 1f else pinContainerAlpha

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
    fun surfaceHi(palette: RovaPalette): Color = surfaceHi(palette.surfaceBase, palette.isLight)

    /**
     * Overload for a themed composable that holds only the [androidx.compose.material3.MaterialTheme]
     * `colorScheme.surface` (== [RovaPalette.surfaceBase], PaletteColorScheme.from :49) and its
     * light/dark polarity — there is no palette CompositionLocal. Byte-identical to
     * [surfaceHi] (RovaPalette) so M8's recovery-card backing matches the sweep's `surfaceHiOf`.
     */
    fun surfaceHi(surfaceBase: Color, isLight: Boolean): Color {
        if (isLight) return Color.White
        val mixed = ContrastMath.compositeAlphaOver(
            255, 255, 255,
            surfaceHiMixFraction.toDouble(),
            (surfaceBase.red * 255f).roundToInt(),
            (surfaceBase.green * 255f).roundToInt(),
            (surfaceBase.blue * 255f).roundToInt(),
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
