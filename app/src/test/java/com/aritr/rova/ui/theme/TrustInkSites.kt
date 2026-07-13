package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Shared, data-driven fixture for the Trust System V1 colour contract (M2).
 *
 * **Authority:** `docs/design/warnings-recovery.html` v1.0 (DESIGN FROZEN 2026-07-09) —
 * `PALETTES` / `SEV` / `FRAMES` / `INK_SITES` / `contrastRows()` (`:1176`–`:1536`).
 *
 * Every value below is **read from the app's own token objects** ([RovaPalette],
 * [RovaWarnings], [RovaTrustTokens]) and never from the spec's CSS `:root` registry.
 * The HTML is the *shape* of the sweep; the app is its *substrate*. That distinction is
 * load-bearing: the spec writes idealized decimals (`rgba(255,255,255,.94)`) while Compose
 * quantizes every sRGB [Color] to 8 bits (`(c * 255f + 0.5f).toInt()`), so
 * `RovaTrustTokens.mediaInk.alpha` reads back as `240/255 = 0.941176`, not `0.94`. Asserting
 * the CSS decimals would prove the spec self-consistent while leaving the shipped tokens
 * unverified.
 *
 * ## Exact (unrounded) compositing
 *
 * [Rgb] carries **Double** channels in `0..255`, deliberately un-quantized. The frozen
 * matrix's tightest pair — Daylight / `Mark · stripchip · hard` — clears its 3.0 bar by
 * **0.0055**. Rounding each intermediate composite to 8 bits shrinks that margin to 0.0011
 * and perturbs the spec's own published fixtures (the snooze chip's `3.61:1` reads `3.59:1`).
 * Intermediate composites are therefore never quantized; only values a *token* or a
 * [Color] actually materializes are (see [surfaceHiOf], and [ResolveInk]'s [Color] return).
 */
internal typealias Rgb = DoubleArray

internal val WHITE_RGB: Rgb = rgbOf(255, 255, 255)

internal fun rgbOf(r: Int, g: Int, b: Int): Rgb =
    doubleArrayOf(r.toDouble(), g.toDouble(), b.toDouble())

/** Exact sRGB channels of a [Color], `0..255`. Alpha is ignored — callers pass it explicitly. */
internal fun Color.rgb(): Rgb = doubleArrayOf(red * 255.0, green * 255.0, blue * 255.0)

/** Materialize an exact triple as a [Color] — quantizes to 8 bits, exactly as Compose paints it. */
internal fun Rgb.toColor(): Color =
    Color((this[0] / 255.0).toFloat(), (this[1] / 255.0).toFloat(), (this[2] / 255.0).toFloat())

/**
 * src-over composite of [fg] at [alpha] onto [bg], unrounded.
 * CSS `color-mix(in srgb, A p%, B)` reduces to the identical expression, so the spec's
 * `over()` and `mixc()` are one primitive here.
 */
internal fun overRgb(fg: Rgb, alpha: Double, bg: Rgb): Rgb =
    ContrastMath.compositeAlphaOverExact(fg[0], fg[1], fg[2], alpha, bg[0], bg[1], bg[2])

internal fun lumRgb(c: Rgb): Double = ContrastMath.relativeLuminance(c[0], c[1], c[2])

internal fun ratioRgb(fg: Rgb, bg: Rgb): Double =
    ContrastMath.contrastRatio(lumRgb(fg), lumRgb(bg))

/** The three media frames a pinned container can float over. HTML `FRAMES` (`:1269`). */
internal enum class MediaFrame(val rgb: Rgb) {
    DARK(rgbOf(14, 16, 22)),
    BRIGHT(rgbOf(196, 204, 214)),
    WHITE(rgbOf(255, 255, 255)),
}

/** HTML `SEV` (`:1198`), in declaration order. Colours come from [RovaWarnings] (Family 2, locked). */
internal enum class Severity(val color: Color) {
    HARD(RovaWarnings.hard),
    SOFT(RovaWarnings.soft),
    ESCALATING(RovaWarnings.escalating),
    ADVISORY(RovaWarnings.advisory),
}

// ── Fill alphas the frozen spec fixes but M1 deliberately left un-tokenized ──────────
// Each lands as a named token WITH ITS FIRST CONSUMER (parity plan §5, M1 note (a)).

/** Sheet 56dp emblem + banner icon box severity fill. HTML `INK_SITES` `.16`. Token arrives in M6. */
internal const val SEV_ICON_TILE_FILL_ALPHA: Double = 0.16

/** History-strip card + settings-chip fill: `color-mix(sev 8%, surface)`. Token arrives in M7. */
internal const val SEV_TINT_FILL_ALPHA: Double = 0.08

// ── Backings, derived from the shipped tokens ────────────────────────────────────────

internal fun pinRgb(): Rgb = RovaTrustTokens.pinSurface.rgb()

/** The pinned capsule composited over the live media frame. HTML `capOf()` (`:1280`). */
internal fun capsuleRgb(frame: Rgb): Rgb =
    overRgb(pinRgb(), RovaTrustTokens.pinContainerAlpha.toDouble(), frame)

internal fun surfaceOf(palette: RovaPalette): Rgb = palette.surfaceBase.rgb()

/**
 * The elevated themed container. Unlike every other backing this is a **materialized token**
 * ([RovaTrustTokens.surfaceHi] returns a [Color]), so it is 8-bit — Aurora `#272934`. The spec's
 * inspector reads the unrounded mix; production paints the rounded one, and the sweep asserts
 * what production paints.
 */
internal fun surfaceHiOf(palette: RovaPalette): Rgb = RovaTrustTokens.surfaceHi(palette).rgb()

/** HTML `tintOf()` (`:1281`) — the strip / settings-chip fill. */
internal fun tintOf(palette: RovaPalette, severity: Color): Rgb =
    overRgb(severity.rgb(), SEV_TINT_FILL_ALPHA, surfaceOf(palette))

/** HTML `tHighOf()` (`:1273`) — the palette's high ink composited onto [bg]. */
internal fun tHighOver(palette: RovaPalette, bg: Rgb): Rgb =
    overRgb(palette.textHigh.rgb(), palette.textHigh.alpha.toDouble(), bg)

/**
 * HTML `quietOf()` (`:1275`) — `quietTextColor(isDark, onSurfaceVariant, dimAlpha)` composited
 * onto [bg]: alpha-dimmed on dark schemes, solid on light. Mirrors [quietTextColor] exactly.
 */
internal fun quietOver(palette: RovaPalette, bg: Rgb): Rgb {
    // `onSurfaceVariant` arrives from the M3 colour scheme OPAQUE; the dim alpha is applied by the
    // seam, not carried by the source colour. Passing `palette.textDim` verbatim would leak its
    // 0.60 alpha into the light branch, where the spec (and `quietTextColor`) paint solid ink.
    //
    // The frozen spec's `quietOf` (`:1275`) models the scheme's variant ink as the palette's
    // `tDim` hue, which is what this reproduces. When M7/M8 route the real surfaces through
    // `rovaQuietText`, re-check that `MaterialTheme.colorScheme.onSurfaceVariant` still equals
    // `textDim`'s hue — if `PaletteColorScheme` ever derives it otherwise, this proxy (and the
    // spec's model of it) drifts from what the app paints.
    val quiet = quietTextColor(
        isDark = !palette.isLight,
        onSurfaceVariant = palette.textDim.copy(alpha = 1f),
        dimAlpha = palette.textDim.alpha,
    )
    return overRgb(quiet.rgb(), quiet.alpha.toDouble(), bg)
}

/**
 * One place a hue lands as ink, with the backing it ACTUALLY sits on.
 * HTML `INK_SITES` (`:1288`–`:1314`) — all eight, verbatim. A site that is not listed is not
 * resolved and not swept.
 *
 * Each role receives `(palette, severityColor, frame)` and returns the backing behind that ink.
 */
internal class InkSite(
    val key: String,
    val top: (RovaPalette, Color, Rgb) -> Rgb,
    val dot: ((RovaPalette, Color, Rgb) -> Rgb)? = null,
    val lbl: ((RovaPalette, Color, Rgb) -> Rgb)? = null,
    val acc: ((RovaPalette, Color, Rgb) -> Rgb)? = null,
)

private fun mediaInkAlpha(): Double = RovaTrustTokens.mediaInk.alpha.toDouble()
private fun sevChipFill(): Double = RovaTrustTokens.sevChipFillAlpha.toDouble()

internal val INK_SITES: List<InkSite> = listOf(
    InkSite(
        key = "sheeticon",
        dot = { _, sev, _ -> overRgb(sev.rgb(), SEV_ICON_TILE_FILL_ALPHA, pinRgb()) },
        top = { _, _, _ -> overRgb(WHITE_RGB, mediaInkAlpha(), pinRgb()) },
    ),
    InkSite(
        key = "pinchip",
        dot = { _, sev, _ -> overRgb(sev.rgb(), sevChipFill(), pinRgb()) },
        lbl = { _, sev, _ -> overRgb(sev.rgb(), sevChipFill(), pinRgb()) },
        top = { _, _, _ -> overRgb(WHITE_RGB, mediaInkAlpha(), pinRgb()) },
    ),
    InkSite(
        key = "banner",
        dot = { _, sev, frame -> overRgb(sev.rgb(), SEV_ICON_TILE_FILL_ALPHA, capsuleRgb(frame)) }, // icon box
        lbl = { _, sev, frame -> overRgb(sev.rgb(), sevChipFill(), capsuleRgb(frame)) },            // Stop pill
        top = { _, _, frame -> overRgb(WHITE_RGB, mediaInkAlpha(), capsuleRgb(frame)) },
    ),
    InkSite(
        key = "snooze",
        dot = { _, _, frame -> capsuleRgb(frame) },
        top = { _, _, frame -> overRgb(WHITE_RGB, mediaInkAlpha(), capsuleRgb(frame)) },
    ),
    InkSite(
        key = "chip",
        dot = { p, sev, _ -> overRgb(sev.rgb(), sevChipFill(), surfaceOf(p)) },
        lbl = { p, sev, _ -> overRgb(sev.rgb(), sevChipFill(), surfaceOf(p)) },
        top = { p, _, _ -> tHighOver(p, surfaceOf(p)) },
    ),
    InkSite(
        key = "stripchip",
        dot = { p, sev, _ -> overRgb(sev.rgb(), sevChipFill(), tintOf(p, sev)) },
        lbl = { p, sev, _ -> overRgb(sev.rgb(), sevChipFill(), tintOf(p, sev)) },
        top = { p, sev, _ -> tHighOver(p, tintOf(p, sev)) },
    ),
    InkSite(
        key = "set",
        dot = { p, sev, _ -> tintOf(p, sev) },
        acc = { p, sev, _ -> tintOf(p, sev) },
        top = { p, sev, _ -> tHighOver(p, tintOf(p, sev)) },
    ),
    InkSite(
        key = "recov",
        dot = { p, _, _ -> surfaceHiOf(p) },
        acc = { p, _, _ -> surfaceHiOf(p) },
        top = { p, _, _ -> tHighOver(p, surfaceHiOf(p)) },
    ),
)
