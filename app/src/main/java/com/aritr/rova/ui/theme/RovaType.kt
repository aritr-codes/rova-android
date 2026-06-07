package com.aritr.rova.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Liquid-glass type scale (ADR-0028 §3.1): the Inter ramp as named roles.
 * Hierarchy by weight + size; de-emphasis is by ALPHA (text color), never a
 * second hue. All sizes are `sp` (dynamic type); no hard line heights.
 * Counters/timers use the `tnum` font feature. This is ADDITIVE — the global
 * `Typography` in Theme.kt is untouched; surfaces adopt these per migration.
 */
object RovaType {
    val display = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W800, fontSize = 34.sp, letterSpacing = (-0.5).sp)
    val headline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W700, fontSize = 26.sp)
    val title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W600, fontSize = 20.sp)
    val subtitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W600, fontSize = 17.sp)
    val bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W400, fontSize = 15.sp)
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W400, fontSize = 13.sp)
    val label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W600, fontSize = 12.sp)
    val caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.W600, fontSize = 10.sp, letterSpacing = 1.4.sp)

    /** Tabular-figures variant for counters/timers (no width jitter as digits change). */
    val counter = body.copy(fontFeatureSettings = "tnum")
}
