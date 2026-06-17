package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Bespoke System-D glyphs (ADR-0031 §5), authored from `board-3-semantic.html` on the 24-grid,
 * 1.9px monoline, round caps/joins. Placeholder colors are overridden by `Icon(tint = …)` in
 * [com.aritr.rova.ui.components.SemanticIcon]. This is the start of the bespoke `RovaGlyphs` home;
 * `RecordChromeIcons` folds in here in a later slice.
 */
object RovaGlyphs {
    // Brush placeholder, overridden by `Icon(tint = …)`. MUST be declared BEFORE the glyph vals
    // below: object properties initialize top-to-bottom, so a glyph referencing a later-declared
    // `val PLACEHOLDER` would build its paths with a NULL brush → geometry present but nothing paints
    // (device-confirmed invisible Record FAB/nav, 2026-06-17). `SW` is a `const val` (compile-time
    // inlined) so its position is irrelevant; PLACEHOLDER is a runtime `val` and its position is not.
    private val PLACEHOLDER = SolidColor(Color.Black)
    private const val SW = 1.9f

    // Library — stacked frames (back = outline, top = accent). Un-collides Play (spec §8).
    val Library = RovaGlyph(
        outline = glyph { strokePath { roundRect(3.5f, 8f, 13f, 11f, 2f) } },
        accent = glyph { strokePath { roundRect(6f, 4.2f, 13f, 9.4f, 2f) } },
    )

    // Settings — soft 8-spoke gear (ring + spokes = outline, center dot = accent). spec §7.1.
    val Settings = RovaGlyph(
        outline = glyph {
            strokePath {
                circle(12f, 12f, 4.3f)
                seg(12f, 3.4f, 12f, 5.7f)
                seg(12f, 18.3f, 12f, 20.6f)
                seg(3.4f, 12f, 5.7f, 12f)
                seg(18.3f, 12f, 20.6f, 12f)
                seg(5.9f, 5.9f, 7.5f, 7.5f)
                seg(16.5f, 16.5f, 18.1f, 18.1f)
                seg(18.1f, 5.9f, 16.5f, 7.5f)
                seg(7.5f, 16.5f, 5.9f, 18.1f)
            }
        },
        accent = glyph { fillPath { circle(12f, 12f, 1.8f) } },
    )

    // Sort — state-free decreasing bars (top two = outline, short bottom = accent). spec §7.2.
    val Sort = RovaGlyph(
        outline = glyph {
            strokePath {
                seg(5f, 7f, 18f, 7f)
                seg(5f, 12f, 14f, 12f)
            }
        },
        accent = glyph { strokePath { seg(5f, 17f, 10f, 17f) } },
    )

    // Record — solid accent disc, the FAB capture action (mono). spec §8 / canonical map.
    val Record = RovaGlyph(
        outline = glyph { fillPath { circle(12f, 12f, 6.8f) } },
    )

    // ── authoring helpers ───────────────────────────────────────────────────
    // (PLACEHOLDER + SW are declared at the TOP of the object — see the init-order note there.)

    private fun glyph(build: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply(build).build()

    private fun ImageVector.Builder.strokePath(block: PathBuilder.() -> Unit) {
        path(
            stroke = PLACEHOLDER, strokeLineWidth = SW,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    private fun ImageVector.Builder.fillPath(block: PathBuilder.() -> Unit) {
        path(fill = PLACEHOLDER, pathBuilder = block)
    }

    /** A straight segment (SVG `M x1 y1 L x2 y2`). */
    private fun PathBuilder.seg(x1: Float, y1: Float, x2: Float, y2: Float) {
        moveTo(x1, y1); lineTo(x2, y2)
    }

    /**
     * A full circle as two semicircle arcs (SVG `<circle cx cy r>`). Each arc is exactly 180° →
     * `isMoreThanHalf = false`. (large-arc-flag `true` requests an arc >180° and is renderer-dependent
     * at exactly 180° — codex-flagged; matters for the filled disc + gear ring.)
     */
    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx, cy - r)
        arcToRelative(r, r, 0f, false, true, 0f, 2 * r)
        arcToRelative(r, r, 0f, false, true, 0f, -2 * r)
        close()
    }

    /** A rounded rect (SVG `<rect x y width height rx>`). */
    private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, rx: Float) {
        moveTo(x + rx, y)
        lineTo(x + w - rx, y); arcToRelative(rx, rx, 0f, false, true, rx, rx)
        lineTo(x + w, y + h - rx); arcToRelative(rx, rx, 0f, false, true, -rx, rx)
        lineTo(x + rx, y + h); arcToRelative(rx, rx, 0f, false, true, -rx, -rx)
        lineTo(x, y + rx); arcToRelative(rx, rx, 0f, false, true, rx, -rx)
        close()
    }
}
