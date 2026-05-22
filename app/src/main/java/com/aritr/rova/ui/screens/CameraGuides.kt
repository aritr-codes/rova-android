package com.aritr.rova.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.aritr.rova.ui.theme.RecordChromeTokens

/**
 * Decorative camera-guide overlays — re-skin of the `.camera-grid` and
 * `.focus-frame` layers in `mockups/new_uiux/01-record-home.html`.
 *
 * Stateless and **non-interactive** (no `clickable` / `pointerInput`) — it
 * cannot intercept camera-area touches. The caller sizes the host (a P+L
 * preview zone, or the single-mode preview area) and this fills it. When
 * [visible] is false nothing is emitted — the "Camera guides" app-setting is
 * off. The focus brackets are static decoration, NOT a tap-to-focus target.
 */
@Composable
fun CameraGuides(visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    Box(modifier) {
        CameraGrid(Modifier.fillMaxSize())
        // CSS `translate(-50%,-60%)` puts the frame centre 10 % of its own
        // height above the parent centre — centre-align then offset up.
        FocusFrame(
            Modifier
                .align(Alignment.Center)
                .offset(y = -(RecordChromeTokens.focusFrameSize * 0.1f)),
        )
    }
}

/** `.camera-grid` — faint composition grid, one line per cell from the origin. */
@Composable
private fun CameraGrid(modifier: Modifier = Modifier) {
    val color = RecordChromeTokens.cameraGridLine
    Canvas(modifier) {
        val cellW = RecordChromeTokens.cameraGridCellWidth.toPx()
        val cellH = RecordChromeTokens.cameraGridCellHeight.toPx()
        val lineW = RecordChromeTokens.cameraGridLineWidth.toPx()
        var x = cellW
        while (x < size.width) {
            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = lineW)
            x += cellW
        }
        var y = cellH
        while (y < size.height) {
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = lineW)
            y += cellH
        }
    }
}

/**
 * `.focus-frame` — four L-shaped corner brackets in a 60 dp square. Each
 * coordinate is inset by half the stroke width so the 1.5 dp strokes sit
 * fully inside the square instead of half-clipping on the edge.
 */
@Composable
private fun FocusFrame(modifier: Modifier = Modifier) {
    val stroke = RecordChromeTokens.focusFrameStroke
    Canvas(modifier.size(RecordChromeTokens.focusFrameSize)) {
        val arm = RecordChromeTokens.focusFrameCornerArm.toPx()
        val w = RecordChromeTokens.focusFrameStrokeWidth.toPx()
        val i = w / 2f
        val s = size.width
        // top-left
        drawLine(stroke, Offset(i, i), Offset(i + arm, i), strokeWidth = w)
        drawLine(stroke, Offset(i, i), Offset(i, i + arm), strokeWidth = w)
        // top-right
        drawLine(stroke, Offset(s - i - arm, i), Offset(s - i, i), strokeWidth = w)
        drawLine(stroke, Offset(s - i, i), Offset(s - i, i + arm), strokeWidth = w)
        // bottom-right
        drawLine(stroke, Offset(s - i - arm, s - i), Offset(s - i, s - i), strokeWidth = w)
        drawLine(stroke, Offset(s - i, s - i - arm), Offset(s - i, s - i), strokeWidth = w)
        // bottom-left
        drawLine(stroke, Offset(i, s - i), Offset(i + arm, s - i), strokeWidth = w)
        drawLine(stroke, Offset(i, s - i - arm), Offset(i, s - i), strokeWidth = w)
    }
}
