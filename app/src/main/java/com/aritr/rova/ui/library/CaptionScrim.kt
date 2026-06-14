package com.aritr.rova.ui.library

/**
 * ADR-0030 / spec §8 — structural caption/badge scrim guaranteeing ≥4.5:1 for
 * white caption text over an arbitrary video frame (worst case: a white frame).
 * A pre-tinted scrim, NOT a live blur (honors checkRecordSurfaceNoBlur + GPU
 * cost). Channels are sRGB 0..255; the composable reads them into Compose Color.
 * AA is proven by CaptionScrimTest against ContrastMath — not a token gate
 * (the background is an arbitrary frame, not a token).
 */
object CaptionScrim {
    const val SCRIM_R = 0
    const val SCRIM_G = 0
    const val SCRIM_B = 0
    const val SCRIM_ALPHA = 0.62

    const val TEXT_R = 255
    const val TEXT_G = 255
    const val TEXT_B = 255
}
