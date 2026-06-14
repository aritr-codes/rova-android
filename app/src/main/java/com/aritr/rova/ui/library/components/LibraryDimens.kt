package com.aritr.rova.ui.library.components

import androidx.compose.ui.unit.dp

/**
 * Shared Library spacing/shape tokens (polish pass, 2026-06-14). One source of truth for corner radii,
 * paddings, icon sizes, and the hero footprint so every component reads as one Liquid Glass system rather
 * than independently-tuned pieces. Keep components referencing these instead of inline literals.
 */
object LibraryDimens {
    /** Outer horizontal page padding (grid/list/hero share this gutter). */
    val screenPadH = 12.dp
    /** Gap between grid tiles. */
    val gridGutter = 6.dp

    /** Card / tile corner radius (grid + list). */
    val cardRadius = 14.dp
    /** Hero media corner radius (slightly larger — it's the focal element). */
    val heroRadius = 18.dp
    /** Overlay pill / chip-ish corner radius. */
    val pillRadius = 8.dp

    /** Hero media height — capped so the hero highlights without dominating the viewport (~30% shorter). */
    val heroHeight = 176.dp

    /** Quick-action / batch icon glyph size. */
    val actionIcon = 20.dp
    /** Top-bar nav/toggle glyph size. */
    val navIcon = 22.dp

    /** Compact vertical padding inside the glass top bars (excludes the system-bar inset). */
    val topBarPadV = 6.dp
    /** Day section label vertical padding. */
    val sectionPadV = 6.dp
}
