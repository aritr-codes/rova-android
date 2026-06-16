package com.aritr.rova.ui.library.components

import androidx.compose.ui.unit.dp

/**
 * Shared Library spacing/shape tokens. One source of truth for corner radii, paddings, icon sizes,
 * the hero footprint, and the semantic alphas so every component reads as one Liquid Glass system
 * rather than independently-tuned pieces. Keep components referencing these instead of inline literals.
 *
 * Polish P1 (2026-06-15) — extended to a Record-aligned scale (edges 16 / cards 18 / hero 20 / pills 11)
 * to match [com.aritr.rova.ui.theme.RecordChromeTokens]; added scrim/divider/selection/empty alphas,
 * session-identity tokens (P3/P6), and floating-sheet tokens (P8).
 */
object LibraryDimens {
    /** Outer horizontal page padding (grid/list/hero share this gutter). Record edge = 16dp. */
    val screenPadH = 16.dp
    /** Gap between grid tiles. */
    val gridGutter = 6.dp

    /** Card / tile corner radius (grid + list). Matches Record's card family (18–22dp). */
    val cardRadius = 18.dp
    /** Hero media corner radius (focal element — one step above cards). */
    val heroRadius = 20.dp
    /** Overlay pill / chip-ish corner radius. Matches Record loopPill = 11dp. */
    val pillRadius = 11.dp

    /** Hero media height — showcase footprint (overlay caption lives inside this box). */
    val heroHeight = 200.dp

    /** Quick-action / batch icon glyph size. */
    val actionIcon = 20.dp
    /** Top-bar nav/toggle glyph size. */
    val navIcon = 22.dp

    /** Compact vertical padding inside the glass top bars (excludes the system-bar inset). */
    val topBarPadV = 6.dp
    /** Day section label vertical padding. */
    val sectionPadV = 6.dp

    // ── Polish P1: card rhythm + semantic alphas ─────────────────────────
    /** Vertical padding inside a grid/list card (Record rhythm 6–8dp). */
    val cardPadV = 6.dp
    /** Badge / overlay-pill content padding (was inline 6.dp/2.dp). */
    val badgePadH = 8.dp
    val badgePadV = 3.dp
    /** Caption-bar inner padding (was inline 8/8/18/6). */
    val captionPadH = 10.dp
    val captionPadTop = 18.dp
    val captionPadBottom = 8.dp

    // Edge / scrim COLOURS moved to the theme layer (M1, 2026-06-16): hairline edges now come from the
    // active palette (LibraryColors.cardEdge), and the selection-ring / hero-scrim / overlay alphas are
    // locked tokens in LibraryColorSpec (CaptionScrim-backed). Only stroke widths stay here.
    /** Selection-ring stroke width. */
    val selectionEdgeWidth = 1.5.dp
    /** Card frame edge stroke width (glass-consistent 1dp). */
    val cardEdgeWidth = 1.dp
    /** Empty-state icon ring alpha (was hardcoded 0.4f). */
    val emptyIconAlpha = 0.40f

    // ── Polish P6/P3: usage line + session-identity ──────────────────────
    /** Status dot diameter on list rows / cards (recovered/interrupted). */
    val statusDotSize = 6.dp
    /** Usage fill-bar height. */
    val usageBarHeight = 4.dp

    // ── Polish P8: floating "Brave-style" item sheet ─────────────────────
    /** Gap between the floating sheet and the screen edges (left/right/bottom, ABOVE the nav-bar inset). */
    val sheetEdgeGap = 12.dp
    /** Floating sheet corner radius (all four corners — it floats, so not just the top). */
    val sheetCornerRadius = 28.dp
    /** Floating sheet shadow/elevation. */
    val sheetElevation = 12.dp
    /** Max width so the sheet reads as a card on wide screens (tablets/landscape). */
    val sheetMaxWidth = 560.dp
}
