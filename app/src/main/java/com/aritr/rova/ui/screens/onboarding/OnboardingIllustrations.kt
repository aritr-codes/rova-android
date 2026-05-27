package com.aritr.rova.ui.screens.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * M4 (2026-05-27) Onboarding illustrations ported from
 * `mockups/new_uiux/08b-onboarding.html` SVG to Compose [Canvas].
 *
 * Three @Composables: [OnboardingClockOrbit], [OnboardingClipMerge],
 * [OnboardingCamera]. Sized via the canvas modifier from the caller —
 * defaults are 210 dp (matches mockup hero size) for the walkthrough
 * illustrations and 56 dp for the camera glyph inside its 132 dp tile
 * (the tile background is drawn by the caller in [OnboardingSlide]).
 *
 * Canvas (not [androidx.compose.ui.graphics.vector.ImageVector]) because
 * the bespoke illustrations are too complex for the vector-builder DSL
 * (the orbit illustration alone is 4 arcs + 8 circles + 12 strokes).
 * Canvas keeps the per-illustration LOC under 80 and reads straight
 * against the SVG source.
 *
 * Pure-JVM testability: Canvas composables don't render under
 * `isReturnDefaultValues = true`. No JVM unit tests for these — visual
 * verification happens on hardware (M4 Task 8 hardware smoke checklist).
 *
 * Badge labels and file-metadata text overlays from the mockup
 * ("30s · 5 min · ×6" / "your_video.mp4") are intentionally NOT drawn
 * here this slice — see plan §Out of scope item 7 ("Illustration badge
 * labels"). The badge SHAPES still render and read visually as params.
 */

private val Indigo = Color(0xFF5B7FFF)
private val Mint = Color(0xFF34D399)

@Composable
fun OnboardingClockOrbit(
    contentDesc: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(210.dp)) {
        Canvas(
            modifier = Modifier
                .size(210.dp)
                .semantics { contentDescription = contentDesc }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val scale = size.width / 210f

            // outer haze
            drawCircle(color = Indigo.copy(alpha = 0.04f), radius = 88f * scale, center = Offset(cx, cy))
            drawCircle(color = Indigo.copy(alpha = 0.07f), radius = 88f * scale, center = Offset(cx, cy), style = Stroke(width = 1f))

            // dashed orbit
            drawCircle(
                color = Indigo.copy(alpha = 0.13f),
                radius = 66f * scale,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            )

            // top + bottom arcs
            val arcStroke = Stroke(width = 2.8f, cap = StrokeCap.Round)
            val arcTopLeft = Offset(cx - 63f * scale, cy - 63f * scale)
            val arcSize = Size(126f * scale, 126f * scale)
            drawArc(Indigo.copy(alpha = 0.55f), 200f, 140f, false, arcTopLeft, arcSize, style = arcStroke)
            drawArc(Indigo.copy(alpha = 0.55f), 20f, 140f, false, arcTopLeft, arcSize, style = arcStroke)

            // center clock face
            drawCircle(Indigo.copy(alpha = 0.07f), radius = 36f * scale, center = Offset(cx, cy))
            drawCircle(Indigo.copy(alpha = 0.18f), radius = 36f * scale, center = Offset(cx, cy), style = Stroke(width = 1.5f))
            drawCircle(Indigo.copy(alpha = 0.11f), radius = 23f * scale, center = Offset(cx, cy))
            drawCircle(Indigo.copy(alpha = 0.28f), radius = 23f * scale, center = Offset(cx, cy), style = Stroke(width = 1f))

            // tick marks at 12 / 3 / 6 / 9
            val tickPaint = Color.White.copy(alpha = 0.28f)
            drawLine(tickPaint, Offset(cx, cy - 21f * scale), Offset(cx, cy - 18f * scale), strokeWidth = 1.5f, cap = StrokeCap.Round)
            drawLine(tickPaint, Offset(cx + 21f * scale, cy), Offset(cx + 18f * scale, cy), strokeWidth = 1.5f, cap = StrokeCap.Round)
            drawLine(tickPaint, Offset(cx, cy + 21f * scale), Offset(cx, cy + 18f * scale), strokeWidth = 1.5f, cap = StrokeCap.Round)
            drawLine(tickPaint, Offset(cx - 21f * scale, cy), Offset(cx - 18f * scale, cy), strokeWidth = 1.5f, cap = StrokeCap.Round)

            // clock hands
            drawLine(Color.White.copy(alpha = 0.72f), Offset(cx, cy), Offset(cx, cy - 14f * scale), strokeWidth = 2f, cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(cx, cy), Offset(cx + 10f * scale, cy + 6f * scale), strokeWidth = 1.5f, cap = StrokeCap.Round)
            drawCircle(Color.White.copy(alpha = 0.8f), radius = 2.5f * scale, center = Offset(cx, cy))

            // badge tiles (no text overlays this slice — see KDoc + plan §Out of scope #7)
            drawRoundRect(
                color = Indigo.copy(alpha = 0.18f),
                topLeft = Offset(142f * scale, 55f * scale),
                size = Size(30f * scale, 19f * scale),
                cornerRadius = CornerRadius(6f * scale, 6f * scale)
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.06f),
                topLeft = Offset(38f * scale, 55f * scale),
                size = Size(30f * scale, 19f * scale),
                cornerRadius = CornerRadius(6f * scale, 6f * scale)
            )
            drawRoundRect(
                color = Mint.copy(alpha = 0.16f),
                topLeft = Offset(142f * scale, 136f * scale),
                size = Size(30f * scale, 19f * scale),
                cornerRadius = CornerRadius(6f * scale, 6f * scale)
            )
        }
    }
}

@Composable
fun OnboardingClipMerge(
    contentDesc: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(210.dp)) {
        Canvas(
            modifier = Modifier
                .size(210.dp)
                .semantics { contentDescription = contentDesc }
        ) {
            val scale = size.width / 210f

            // glow under merged file
            drawOval(
                color = Indigo.copy(alpha = 0.06f),
                topLeft = Offset(47f * scale, 154f * scale),
                size = Size(116f * scale, 36f * scale)
            )

            val clipCorner = CornerRadius(9f * scale, 9f * scale)
            val thumbCorner = CornerRadius(4f * scale, 4f * scale)
            val cardFill = Indigo.copy(alpha = 0.09f)
            val cardStroke = Indigo.copy(alpha = 0.26f)

            fun drawClip(x: Float, y: Float) {
                drawRoundRect(cardFill, topLeft = Offset(x * scale, y * scale), size = Size(42f * scale, 56f * scale), cornerRadius = clipCorner)
                drawRoundRect(cardStroke, topLeft = Offset(x * scale, y * scale), size = Size(42f * scale, 56f * scale), cornerRadius = clipCorner, style = Stroke(width = 1.2f))
                drawRoundRect(Indigo.copy(alpha = 0.14f), topLeft = Offset((x + 4f) * scale, (y + 4f) * scale), size = Size(34f * scale, 22f * scale), cornerRadius = thumbCorner)
            }
            drawClip(10f, 60f)
            drawClip(59f, 46f)   // raised middle clip
            drawClip(108f, 60f)

            // down arrow
            val arrowColor = Indigo.copy(alpha = 0.44f)
            drawLine(arrowColor, Offset(105f * scale, 124f * scale), Offset(105f * scale, 142f * scale), strokeWidth = 2f, cap = StrokeCap.Round)
            val arrowhead = Path().apply {
                moveTo(99f * scale, 137f * scale)
                lineTo(105f * scale, 145f * scale)
                lineTo(111f * scale, 137f * scale)
            }
            drawPath(arrowhead, color = arrowColor, style = Stroke(width = 2f, cap = StrokeCap.Round))

            // merged file card
            val mergedCorner = CornerRadius(13f * scale, 13f * scale)
            drawRoundRect(Indigo.copy(alpha = 0.12f), topLeft = Offset(48f * scale, 150f * scale), size = Size(116f * scale, 50f * scale), cornerRadius = mergedCorner)
            drawRoundRect(Indigo.copy(alpha = 0.44f), topLeft = Offset(48f * scale, 150f * scale), size = Size(116f * scale, 50f * scale), cornerRadius = mergedCorner, style = Stroke(width = 1.5f))

            // play button
            drawCircle(Indigo.copy(alpha = 0.22f), radius = 14f * scale, center = Offset(76f * scale, 175f * scale))
            drawCircle(Indigo.copy(alpha = 0.46f), radius = 14f * scale, center = Offset(76f * scale, 175f * scale), style = Stroke(width = 1f))
            val triangle = Path().apply {
                moveTo(72f * scale, 169f * scale)
                lineTo(84f * scale, 175f * scale)
                lineTo(72f * scale, 181f * scale)
                close()
            }
            drawPath(triangle, color = Indigo)

            // green check badge
            drawCircle(Mint.copy(alpha = 0.15f), radius = 10f * scale, center = Offset(148f * scale, 156f * scale))
            drawCircle(Mint.copy(alpha = 0.44f), radius = 10f * scale, center = Offset(148f * scale, 156f * scale), style = Stroke(width = 1f))
            val checkPath = Path().apply {
                moveTo(143f * scale, 156f * scale)
                lineTo(147f * scale, 160f * scale)
                lineTo(153f * scale, 152f * scale)
            }
            drawPath(checkPath, color = Mint.copy(alpha = 0.92f), style = Stroke(width = 1.8f, cap = StrokeCap.Round))
        }
    }
}

@Composable
fun OnboardingCamera(
    contentDesc: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(56.dp)) {
        Canvas(
            modifier = Modifier
                .size(56.dp)
                .semantics { contentDescription = contentDesc }
        ) {
            val scale = size.width / 24f
            val stroke = Stroke(width = 1.6f * scale, cap = StrokeCap.Round)
            val color = Indigo.copy(alpha = 0.94f)

            // body — rounded rect from (1,5) size 15x14
            drawRoundRect(
                color = color,
                topLeft = Offset(1f * scale, 5f * scale),
                size = Size(15f * scale, 14f * scale),
                cornerRadius = CornerRadius(2f * scale, 2f * scale),
                style = stroke
            )
            // lens triangle — (23,7) → (16,12) → (23,17) → close
            val lens = Path().apply {
                moveTo(23f * scale, 7f * scale)
                lineTo(16f * scale, 12f * scale)
                lineTo(23f * scale, 17f * scale)
                close()
            }
            drawPath(lens, color = color, style = stroke)
        }
    }
}
