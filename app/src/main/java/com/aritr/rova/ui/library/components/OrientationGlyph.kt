package com.aritr.rova.ui.library.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.aritr.rova.ui.library.LibraryOrientation

/**
 * Bento meta-pill orientation cue (frozen anatomy, spec 2026-07-04 / Task 4) — a drawn 12x12-viewport
 * rounded-rect OUTLINE: a tall frame for PORTRAIT, a wide (transposed) frame for LANDSCAPE. Always
 * White@0.95 on the dark meta-pill scrim — a fixed decorative mark, not a themed icon, so it paints
 * directly via [Canvas] rather than [SemanticIcon] (checkSemanticIconNoRawAlpha guards Icon/SemanticIcon
 * tints only; this glyph has no tint argument at all). Caller decides whether to render it at all —
 * a null [LibraryOrientation] means no glyph (see [com.aritr.rova.ui.library.LibraryRow.orientation]).
 */
@Composable
fun OrientationGlyph(orientation: LibraryOrientation, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val viewport = 12f
        val scale = size.width / viewport
        val strokeWidthPx = 1.4f * (11f / 12f) * scale
        val (x, y, w, h) = when (orientation) {
            LibraryOrientation.PORTRAIT -> floatArrayOf(3.4f, 1f, 5.2f, 10f)
            LibraryOrientation.LANDSCAPE -> floatArrayOf(1f, 3.4f, 10f, 5.2f)
        }
        drawRoundRect(
            color = Color.White.copy(alpha = 0.95f),
            topLeft = Offset(x * scale, y * scale),
            size = Size(w * scale, h * scale),
            cornerRadius = CornerRadius(1.6f * scale, 1.6f * scale),
            style = Stroke(width = strokeWidthPx),
        )
    }
}
