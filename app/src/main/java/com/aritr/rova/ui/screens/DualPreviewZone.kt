// app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt
package com.aritr.rova.ui.screens

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.theme.RecordChromeTokens
import com.aritr.rova.ui.theme.RovaTokens

/**
 * Phase 6.1c — P+L mode preview surface. Two stacked TextureViews per
 * `mockups/new_uiux/01-record-home.html` (portrait zone on top with
 * 9:16 letterboxed preview, landscape zone below with 16:9 letterboxed
 * preview, 2 dp white-14%-alpha divider between).
 *
 * Each TextureView's [SurfaceTexture] is wrapped as a [Surface] and
 * registered with the recording service via [registerPreviewSurface].
 * EglRouter renders the per-side cropped + rotation-corrected sample
 * into the surface (with aspect-fit viewport letterbox). The
 * SurfaceTextureListener handles the full lifecycle including size
 * changes.
 */
@Composable
fun DualPreviewZone(
    registerPreviewSurface: (side: VideoSide, surface: Surface, width: Int, height: Int) -> Unit,
    unregisterPreviewSurface: (side: VideoSide) -> Unit,
    modifier: Modifier = Modifier,
    guidesEnabled: Boolean = true,
) {
    Column(modifier = modifier) {
        // Top zone — portrait preview, weight 352:225 (mockup proportions).
        PreviewZone(
            side = VideoSide.PORTRAIT,
            label = "Portrait · 9:16",
            registerPreviewSurface = registerPreviewSurface,
            unregisterPreviewSurface = unregisterPreviewSurface,
            guidesEnabled = guidesEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .weight(352f),
        )
        // Divider per mockup .cam-split-divider — 2 dp, white 14% alpha.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.14f))
        )
        // Bottom zone — landscape preview, weight 225 of 352:225.
        PreviewZone(
            side = VideoSide.LANDSCAPE,
            label = "Landscape · 16:9",
            registerPreviewSurface = registerPreviewSurface,
            unregisterPreviewSurface = unregisterPreviewSurface,
            guidesEnabled = guidesEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .weight(225f),
        )
    }
}

/**
 * Single zone — hosts a TextureView via AndroidView and a label
 * in the bottom-right corner per mockup .cam-zone-tag styling.
 */
@Composable
private fun PreviewZone(
    side: VideoSide,
    label: String,
    registerPreviewSurface: (side: VideoSide, surface: Surface, width: Int, height: Int) -> Unit,
    unregisterPreviewSurface: (side: VideoSide) -> Unit,
    guidesEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            registerPreviewSurface(side, Surface(st), w, h)
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                            // Re-register; service-side map keyed by side, replaces prior entry.
                            registerPreviewSurface(side, Surface(st), w, h)
                        }
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            unregisterPreviewSurface(side)
                            return true  // framework releases the SurfaceTexture
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Decorative guides — grid + focus brackets — above the
        // camera, below the tag. Renders nothing when the toggle is off.
        CameraGuides(visible = guidesEnabled, modifier = Modifier.fillMaxSize())
        // Recording-frame guide — always on in P+L mode. Above the decorative
        // grid/brackets so capture bounds stay readable. See ADR-0010.
        RecordingFrameGuide(side = side, modifier = Modifier.fillMaxSize())
        // cam-zone-tag — plain uppercase micro-text (mockup .cam-zone-tag:
        // 7.5 sp, weight 500, 1.5 sp tracking, white-32%), bottom-end.
        Text(
            text = label.uppercase(),
            style = RovaTokens.zoneTag,
            color = RecordChromeTokens.zoneTagText,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = RecordChromeTokens.zoneTagPaddingEnd,
                    bottom = RecordChromeTokens.zoneTagPaddingBottom,
                ),
        )
    }
}

/**
 * Recording-frame guide — overlay drawn unconditionally in each P+L zone.
 * Marks the recorded sub-rectangle of the wider preview with a faint 1 dp
 * gray outline plus a low-alpha black scrim over the non-recorded margin.
 * Independent of the decorative "Camera guides" app-setting: this is
 * functional (capture-bounds indicator), not decorative.
 *
 * Stateless and non-interactive. Pure layout math from the zone's measured
 * size + the side's recording aspect — no GL, no Compose state, recomposes
 * only when the host zone resizes.
 *
 * See ADR-0010 and
 * `docs/superpowers/specs/2026-05-22-dualshot-preview-crop-fill-design.md` §5.3.
 */
@Composable
private fun RecordingFrameGuide(side: VideoSide, modifier: Modifier = Modifier) {
    val recordingAspect = when (side) {
        VideoSide.PORTRAIT -> 9f / 16f
        VideoSide.LANDSCAPE -> 16f / 9f
    }
    val outlineColor = RecordChromeTokens.recordingFrameOutline
    val scrimColor = RecordChromeTokens.recordingFrameScrim
    val strokeWidthDp = RecordChromeTokens.recordingFrameStrokeWidth

    Canvas(modifier = modifier) {
        val zoneW = size.width
        val zoneH = size.height
        if (zoneW <= 0f || zoneH <= 0f) return@Canvas
        val zoneAspect = zoneW / zoneH

        // Fit the recording-aspect rectangle inside the zone, centred.
        val (recW, recH) = if (recordingAspect < zoneAspect) {
            // Recording narrower than zone → fit by height, side scrims.
            zoneH * recordingAspect to zoneH
        } else {
            // Recording wider than zone → fit by width, top/bottom scrims.
            zoneW to zoneW / recordingAspect
        }
        val recLeft = (zoneW - recW) / 2f
        val recTop = (zoneH - recH) / 2f

        // Scrim over the non-recorded margin.
        if (recW < zoneW) {
            // Side scrims (portrait case).
            drawRect(scrimColor, topLeft = Offset(0f, 0f),               size = Size(recLeft, zoneH))
            drawRect(scrimColor, topLeft = Offset(recLeft + recW, 0f),   size = Size(zoneW - recLeft - recW, zoneH))
        }
        if (recH < zoneH) {
            // Top/bottom scrims (landscape case).
            drawRect(scrimColor, topLeft = Offset(0f, 0f),               size = Size(zoneW, recTop))
            drawRect(scrimColor, topLeft = Offset(0f, recTop + recH),    size = Size(zoneW, zoneH - recTop - recH))
        }

        // Recording-rect outline. `Stroke.width = strokeWidthDp.toPx()` is the
        // device-pixel thickness; for 1.dp on the Samsung SM-A176B (density
        // ~1.7) that lands on ~2 device pixels.
        drawRect(
            color = outlineColor,
            topLeft = Offset(recLeft, recTop),
            size = Size(recW, recH),
            style = Stroke(width = strokeWidthDp.toPx()),
        )
    }
}
