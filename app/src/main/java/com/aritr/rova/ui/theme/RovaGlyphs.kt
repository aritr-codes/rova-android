package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Bespoke System-D glyphs (ADR-0031 §5), authored from `board-3-semantic.html` on the 24-grid,
 * 1.9px monoline, round caps/joins. Placeholder colors are overridden by `Icon(tint = …)` in
 * [com.aritr.rova.ui.components.SemanticIcon]. This is the one home for bespoke `ImageVector`s
 * (the ex-`RecordChromeIcons` vectors folded in below; enforced by `checkRovaGlyphHome`, ADR-0031 §5).
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

    // ── Brand glyphs (spec Part B, board-3-semantic.html) ───────────────────

    // DualShot — P+L twin frames (outline) + accent capture core. board `ds_twin`.
    val DualShot = RovaGlyph(
        outline = glyph {
            strokePath { roundRect(3.4f, 5.6f, 8.6f, 12.8f, 2f) }
            strokePath { roundRect(11.6f, 9f, 9f, 9f, 2f) }
        },
        accent = glyph { fillPath { circle(11.9f, 12f, 2f) } },
    )

    // Vault — front frame (outline) + back frame & padlock (accent). board `vault_stack`.
    val Vault = RovaGlyph(
        outline = glyph { strokePath { roundRect(3.5f, 8f, 12.5f, 9f, 2f) } },
        accent = glyph {
            strokePath { roundRect(6f, 4f, 12.5f, 9f, 2f) }
            fillPath { roundRect(14f, 14.2f, 6.5f, 5.3f, 1.2f) }
            svgStroke("M15.3 14.2v-1.3a2 2 0 0 1 4 0v1.3")
        },
    )

    // Recovery — two rejoined bracket halves (outline) + dashed seam (accent). board `recov_rejoin`.
    val Recovery = RovaGlyph(
        outline = glyph {
            svgStroke("M10.3 5H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h4.3")
            svgStroke("M13.7 5H18a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-4.3")
        },
        accent = glyph { svgStroke("M12 4.3v3M12 10.5v3M12 16.7v3") },
    )

    // DualSight — frame + 2nd lens (outline) + PiP inset (accent). board `dualsight`.
    val DualSight = RovaGlyph(
        outline = glyph {
            strokePath { roundRect(3.5f, 5.5f, 17f, 13f, 2.3f) }
            strokePath { circle(8.2f, 10.5f, 2.4f) }
        },
        accent = glyph { fillPath { roundRect(12.4f, 11.6f, 6.2f, 5.2f, 1.4f) } },
    )

    // Background-record — backgrounded-app card (outline) + rec dot (accent). board `bg_record`.
    val BackgroundRecord = RovaGlyph(
        outline = glyph {
            svgStroke("M8.5 4.5H18a1.8 1.8 0 0 1 1.8 1.8V15")
            strokePath { roundRect(4.2f, 8f, 11.6f, 11.5f, 2.2f) }
        },
        accent = glyph { fillPath { circle(10f, 13.7f, 2.6f) } },
    )

    // Merge — two streams converge (outline) + join node (accent). board `merge_stitch`.
    val Merge = RovaGlyph(
        outline = glyph {
            svgStroke("M4 7h4M4 17h4M8 7c4 0 4 5 8 5M8 17c4 0 4-5 8-5M16 12h4")
        },
        accent = glyph { fillPath { circle(16.5f, 12f, 1.9f) } },
    )

    // Single (Auto) — one capture frame (outline) + accent capture core. Mono-safe:
    // one frame reads as single-camera capture; the core dot is the duotone channel.
    // (No board source — authored here as the Auto sibling of DualShot's twin frames.)
    val Single = RovaGlyph(
        outline = glyph { strokePath { roundRect(5.5f, 6.5f, 13f, 11f, 2.4f) } },
        accent = glyph { fillPath { circle(12f, 12f, 2f) } },
    )

    // FollowDevice — phone + two auto-rotate arrows (outline) + speaker bar (accent).
    // The rotation arrows are its ONLY differentiator from Portrait, so they live in the
    // MONO outline layer — they must never wash out (ADR-0031 §1 mono-safe). The accent is
    // the speaker bar, matching Portrait/Landscape's duotone channel. (No board source.)
    val FollowDevice = RovaGlyph(
        outline = glyph {
            strokePath { roundRect(9f, 6f, 6f, 12f, 1.8f) }
            svgStroke("M14.5 4c3 0.6 5 2.6 5.6 5.6M20.1 9.6l0.1-2.6M20.1 9.6l-2.6 0.2")
            svgStroke("M9.5 20c-3-0.6-5-2.6-5.6-5.6M3.9 14.4l-0.1 2.6M3.9 14.4l2.6-0.2")
        },
        accent = glyph { strokePath { seg(10.5f, 8.4f, 13.5f, 8.4f) } },
    )

    // ── Orientation (phone outline; owner-chosen over a person glyph) ───────
    // Mono-safe: the outline alone reads as an upright vs rotated phone; the
    // accent speaker bar is the duotone channel. (No board source — authored here.)

    // Portrait — upright phone + top speaker bar.
    val OrientationPortrait = RovaGlyph(
        outline = glyph { strokePath { roundRect(8f, 3f, 8f, 18f, 2.2f) } },
        accent = glyph { strokePath { seg(10.6f, 5.4f, 13.4f, 5.4f) } },
    )

    // Landscape — rotated phone + speaker bar on the left short edge.
    val OrientationLandscape = RovaGlyph(
        outline = glyph { strokePath { roundRect(3f, 8f, 18f, 8f, 2.2f) } },
        accent = glyph { strokePath { seg(5.4f, 10.6f, 5.4f, 13.4f) } },
    )

    // ── FAB lifecycle glyphs (board-3-semantic.html FB row) ─────────────────

    // Record nav/disabled resting form — ring (outline) + accent core. board `rec_ring`.
    // The FAB Disabled state renders this (the action's resting silhouette).
    val RecordRing = RovaGlyph(
        outline = glyph { strokePath { circle(12f, 12f, 8f) } },
        accent = glyph { fillPath { circle(12f, 12f, 3.2f) } },
    )

    // Waiting — hourglass frame (outline) + accent sand (accent). board `waiting`.
    // Pending / scheduled / between intervals (FAB Waiting state).
    val Waiting = RovaGlyph(
        outline = glyph {
            svgStroke("M7 4h10")
            svgStroke("M7 20h10")
            svgStroke("M7.5 4c0 4 4.5 5 4.5 8 0-3 4.5-4 4.5-8")
            svgStroke("M7.5 20c0-4 4.5-5 4.5-8 0 3 4.5 4 4.5 8")
        },
        accent = glyph { svgFill("M12 12.4c-1.3 1.3-2.6 2.2-2.6 5.1h5.2c0-2.9-1.3-3.8-2.6-5.1z") },
    )

    // Processing — 270° arc, spun by the consumer. board `proc_arc`. Mono (single layer).
    val ProcArc = RovaGlyph(
        outline = glyph { svgStroke("M12 4a8 8 0 1 1-7.4 5") },
    )

    // Processing — reduced-motion static fallback: three dots. board `proc_dots`.
    val ProcDots = RovaGlyph(
        outline = glyph {
            fillPath { circle(5.5f, 12f, 1.8f) }
            fillPath { circle(12f, 12f, 1.8f) }
            fillPath { circle(18.5f, 12f, 1.8f) }
        },
    )

    // ── Folded-in record-chrome vectors (ex-RecordChromeIcons, verbatim) ────
    // Single-layer, neutral white, tinted by the consuming Icon/SemanticIcon.
    // Kept at their original viewports/strokes — this was a structural move, not
    // a re-skin (System-D re-authoring is a later visual slice). Superseded
    // library/settings/flipCamera were dropped (duotone RovaGlyphs replace nav;
    // flip switched to CameraFront/CameraRear in B6).

    /** `.cam-ctrl-btn` flash glyph — single lightning bolt. */
    val FlashBolt: ImageVector =
        ImageVector.Builder(
            name = "RovaGlyphFlashBolt",
            defaultWidth = 13.dp, defaultHeight = 15.dp,
            viewportWidth = 13f, viewportHeight = 15f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(7.5f, 1f); lineTo(1.5f, 8.5f); horizontalLineTo(6f)
                lineTo(5f, 14f); lineTo(11.5f, 6.5f); horizontalLineTo(7f)
                lineTo(7.5f, 1f); close()
            }
        }.build()

    /** "switch to FRONT camera" — body + selfie head/shoulders. */
    val CameraFront: ImageVector =
        ImageVector.Builder(
            name = "RovaGlyphCameraFront",
            defaultWidth = 16.dp, defaultHeight = 16.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            val stroke = SolidColor(Color.White)
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 7f); horizontalLineTo(20f); verticalLineTo(19f); horizontalLineTo(4f); close()
            }
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9f, 7f); lineTo(10.5f, 4.5f); horizontalLineTo(13.5f); lineTo(15f, 7f)
            }
            path(stroke = stroke, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(13.5f, 12f)
                arcTo(1.5f, 1.5f, 0f, true, true, 10.5f, 12f)
                arcTo(1.5f, 1.5f, 0f, true, true, 13.5f, 12f)
                close()
            }
            path(stroke = stroke, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9.5f, 17f)
                arcTo(2.5f, 2.5f, 0f, false, true, 14.5f, 17f)
            }
        }.build()

    /** "switch to REAR camera" — body + centred lens ring. */
    val CameraRear: ImageVector =
        ImageVector.Builder(
            name = "RovaGlyphCameraRear",
            defaultWidth = 16.dp, defaultHeight = 16.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            val stroke = SolidColor(Color.White)
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 7f); horizontalLineTo(20f); verticalLineTo(19f); horizontalLineTo(4f); close()
            }
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9f, 7f); lineTo(10.5f, 4.5f); horizontalLineTo(13.5f); lineTo(15f, 7f)
            }
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15f, 13f)
                arcTo(3f, 3f, 0f, true, true, 9f, 13f)
                arcTo(3f, 3f, 0f, true, true, 15f, 13f)
                close()
            }
        }.build()

    /** `.settings-arrow` chevron — points up (expand sheet). */
    val ChevronUp: ImageVector =
        ImageVector.Builder(
            name = "RovaGlyphChevronUp",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(stroke = SolidColor(Color.White), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(18f, 15f); lineTo(12f, 9f); lineTo(6f, 15f)
            }
        }.build()

    /** Play triangle (ex-fabPlay) — used as RovaIcons.Play. */
    val Play: ImageVector =
        ImageVector.Builder(
            name = "RovaGlyphPlay",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(9f, 7f); lineTo(17f, 12f); lineTo(9f, 17f); close()
            }
        }.build()

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

    /** Verbatim SVG `<path d=…>` stroke (round caps/joins, System-D weight). */
    private fun ImageVector.Builder.svgStroke(d: String) {
        addPath(
            pathData = addPathNodes(d),
            stroke = PLACEHOLDER, strokeLineWidth = SW,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }

    /** Verbatim SVG `<path d=…>` fill. */
    private fun ImageVector.Builder.svgFill(d: String) {
        addPath(pathData = addPathNodes(d), fill = PLACEHOLDER)
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
