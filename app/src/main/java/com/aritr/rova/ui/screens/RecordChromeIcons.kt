package com.aritr.rova.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Phase 2 — custom vector icons for the record-screen chrome, reproducing the
 * inline `<svg>` glyphs in `mockups/new_uiux/01-record-home.html` exactly
 * (Material icons are visually off vs the mockup). Path data is verbatim from
 * the mockup; viewport matches each glyph's `viewBox`.
 *
 * Glyphs are authored neutral (white fill / white stroke); the consuming
 * `Icon(..., tint = …)` recolours them, so one `ImageVector` serves every
 * tint state. Each vector is built once via `by lazy`.
 */
object RecordChromeIcons {

    /** `.cam-ctrl-btn` flash glyph — the mockup's single lightning bolt. */
    val flashBolt: ImageVector by lazy {
        ImageVector.Builder(
            name = "RecordChromeFlashBolt",
            defaultWidth = 13.dp, defaultHeight = 15.dp,
            viewportWidth = 13f, viewportHeight = 15f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(7.5f, 1f)
                lineTo(1.5f, 8.5f)
                horizontalLineTo(6f)
                lineTo(5f, 14f)
                lineTo(11.5f, 6.5f)
                horizontalLineTo(7f)
                lineTo(7.5f, 1f)
                close()
            }
        }.build()
    }

    /** `.cam-ctrl-btn` flip-camera glyph — two arcs + two arrowheads. */
    val flipCamera: ImageVector by lazy {
        ImageVector.Builder(
            name = "RecordChromeFlipCamera",
            defaultWidth = 16.dp, defaultHeight = 14.dp,
            viewportWidth = 16f, viewportHeight = 14f,
        ).apply {
            val stroke = SolidColor(Color.White)
            path(stroke = stroke, strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round) {
                moveTo(1.5f, 5f)
                arcTo(6f, 6f, 0f, false, true, 13f, 5f)
            }
            path(stroke = stroke, strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round) {
                moveTo(14.5f, 9f)
                arcTo(6f, 6f, 0f, false, true, 3f, 9f)
            }
            path(stroke = stroke, strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3f)
                lineToRelative(2f, 2f)
                lineToRelative(-2f, 2f)
            }
            path(stroke = stroke, strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 7f)
                lineTo(2f, 9f)
                lineToRelative(2f, 2f)
            }
        }.build()
    }

    /** `.nav-ico` Library glyph — bordered grid. */
    val library: ImageVector by lazy {
        ImageVector.Builder(
            name = "RecordChromeLibrary",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            val stroke = SolidColor(Color.White)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                path(stroke = stroke, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                    moveTo(x1, y1); lineTo(x2, y2)
                }
            // rounded outer rect (rx 2.5)
            path(stroke = stroke, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4.5f, 2f)
                horizontalLineTo(19.5f)
                arcTo(2.5f, 2.5f, 0f, false, true, 22f, 4.5f)
                verticalLineTo(19.5f)
                arcTo(2.5f, 2.5f, 0f, false, true, 19.5f, 22f)
                horizontalLineTo(4.5f)
                arcTo(2.5f, 2.5f, 0f, false, true, 2f, 19.5f)
                verticalLineTo(4.5f)
                arcTo(2.5f, 2.5f, 0f, false, true, 4.5f, 2f)
                close()
            }
            line(7f, 2f, 7f, 22f)
            line(17f, 2f, 17f, 22f)
            line(2f, 12f, 22f, 12f)
            line(2f, 7f, 7f, 7f)
            line(17f, 7f, 22f, 7f)
            line(2f, 17f, 7f, 17f)
            line(17f, 17f, 22f, 17f)
        }.build()
    }

    /** `.nav-ico` Settings glyph — circle + gear. */
    val settings: ImageVector by lazy {
        ImageVector.Builder(
            name = "RecordChromeSettings",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            val stroke = SolidColor(Color.White)
            // inner circle r=3
            path(stroke = stroke, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15f, 12f)
                arcTo(3f, 3f, 0f, true, true, 9f, 12f)
                arcTo(3f, 3f, 0f, true, true, 15f, 12f)
                close()
            }
            // gear body — verbatim mockup path
            path(stroke = stroke, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(19.4f, 15f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, 0.33f, 1.82f)
                lineToRelative(0.06f, 0.06f)
                arcToRelative(2f, 2f, 0f, true, true, -2.83f, 2.83f)
                lineToRelative(-0.06f, -0.06f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -2.83f, -0.33f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -1f, 1.51f)
                verticalLineTo(21f)
                arcToRelative(2f, 2f, 0f, false, true, -4f, 0f)
                verticalLineToRelative(-0.09f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -1.08f, -1.51f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -1.82f, 0.33f)
                lineToRelative(-0.06f, 0.06f)
                arcToRelative(2f, 2f, 0f, true, true, -2.83f, -2.83f)
                lineToRelative(0.06f, -0.06f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, 0.33f, -1.82f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -1.51f, -1f)
                horizontalLineTo(3f)
                arcToRelative(2f, 2f, 0f, false, true, 0f, -4f)
                horizontalLineToRelative(0.09f)
                arcTo(1.65f, 1.65f, 0f, false, false, 4.6f, 9f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -0.33f, -1.82f)
                lineToRelative(-0.06f, -0.06f)
                arcToRelative(2f, 2f, 0f, true, true, 2.83f, -2.83f)
                lineToRelative(0.06f, 0.06f)
                arcTo(1.65f, 1.65f, 0f, false, false, 9f, 4.68f)
                arcTo(1.65f, 1.65f, 0f, false, false, 10f, 3.17f)
                verticalLineTo(3f)
                arcToRelative(2f, 2f, 0f, false, true, 4f, 0f)
                verticalLineToRelative(0.09f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, 1f, 1.51f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, 1.82f, -0.33f)
                lineToRelative(0.06f, -0.06f)
                arcToRelative(2f, 2f, 0f, true, true, 2.83f, 2.83f)
                lineToRelative(-0.06f, 0.06f)
                arcTo(1.65f, 1.65f, 0f, false, false, 19.4f, 9f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, 1.51f, 1f)
                horizontalLineTo(21f)
                arcToRelative(2f, 2f, 0f, false, true, 0f, 4f)
                horizontalLineToRelative(-0.09f)
                arcToRelative(1.65f, 1.65f, 0f, false, false, -1.51f, 1f)
                close()
            }
        }.build()
    }

    /** `.settings-arrow` chevron — points up (expand sheet). */
    val chevronUp: ImageVector by lazy {
        ImageVector.Builder(
            name = "RecordChromeChevronUp",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(stroke = SolidColor(Color.White), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(18f, 15f)
                lineTo(12f, 9f)
                lineTo(6f, 15f)
            }
        }.build()
    }

    /** `.center-btn .start-tri` — the FAB Start triangle (mockup uses a CSS triangle). */
    val fabPlay: ImageVector by lazy {
        ImageVector.Builder(
            name = "RecordChromeFabPlay",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(9f, 7f)
                lineTo(17f, 12f)
                lineTo(9f, 17f)
                close()
            }
        }.build()
    }
}
