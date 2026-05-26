// app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt
package com.aritr.rova.ui.screens

import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
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
        // Divider per mockup .cam-split-divider — softened to alpha 0.06f
        // in Milestone 1 (spec §5 #4). Token: [RecordChromeTokens.camSplitDividerAlpha].
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.White.copy(alpha = RecordChromeTokens.camSplitDividerAlpha))
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
 * Marks the recorded sub-rectangle by darkening the non-recorded margins
 * with a subtle scrim (alpha 0.11f) and, on API 31+, applying a 12.dp
 * Gaussian blur to the scrim regions so the camera content beneath reads
 * as soft frosted glass. Independent of the decorative "Camera guides"
 * app-setting: this is functional (capture-bounds indicator), not
 * decorative — always-on per spec §5 #5.
 *
 * Stateless and non-interactive. Pure layout math via
 * [recordingFrameLayout]; recomposes only when the host zone resizes.
 *
 * Milestone 1 (spec `docs/superpowers/specs/2026-05-26-dualshot-frame-polish-design.md`):
 * the prior 1.dp gray outline (`recordingFrameOutline` / `recordingFrameStrokeWidth`)
 * is removed; the prior 0.22f scrim is halved to 0.11f; API 31+ devices
 * additionally render a frosted-glass blur over the scrim regions.
 *
 * See also ADR-0010 and the original
 * `docs/superpowers/specs/2026-05-22-dualshot-preview-crop-fill-design.md` §5.3.
 */
@Composable
private fun RecordingFrameGuide(side: VideoSide, modifier: Modifier = Modifier) {
    val recordingAspect = when (side) {
        VideoSide.PORTRAIT -> 9f / 16f
        VideoSide.LANDSCAPE -> 16f / 9f
    }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val zoneWidthPx = with(density) { maxWidth.toPx() }
        val zoneHeightPx = with(density) { maxHeight.toPx() }
        val blurRadiusPx = with(density) { RecordChromeTokens.recordingFrameBlurRadius.toPx() }
        // Cache the native RenderEffect — without remember, a fresh native
        // Skia filter would be allocated each recomposition while the
        // TextureView underneath drives continuous redraws. Stable until
        // blurRadiusPx changes (density change → recomposition with new key).
        val blurRenderEffect = remember(blurRadiusPx) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RenderEffect
                    .createBlurEffect(blurRadiusPx, blurRadiusPx, Shader.TileMode.CLAMP)
                    .asComposeRenderEffect()
            } else null
        }
        val layout = recordingFrameLayout(zoneWidthPx, zoneHeightPx, recordingAspect)
        layout.scrimRegions.forEach { region ->
            ScrimRegion(region = region, blurRenderEffect = blurRenderEffect)
        }
    }
}

/**
 * Single scrim region — a Box positioned with absolute offset over the
 * non-recorded margin area. On API 31+ applies a `RenderEffect.createBlurEffect`
 * via `Modifier.graphicsLayer` so the live camera content beneath blurs.
 * On API <31 (Build.VERSION_CODES.S = 31, project minSdk = 24) the modifier
 * is a no-op pass-through; the flat 0.11f scrim alone provides the cue.
 *
 * `Shader.TileMode.CLAMP` prevents edge-darkening at the recording-rect
 * boundary (per spec §6.3).
 */
@Composable
private fun ScrimRegion(
    region: FrameRect,
    blurRenderEffect: androidx.compose.ui.graphics.RenderEffect?,
) {
    val density = LocalDensity.current
    val offsetX = with(density) { region.left.toDp() }
    val offsetY = with(density) { region.top.toDp() }
    val widthDp = with(density) { region.width.toDp() }
    val heightDp = with(density) { region.height.toDp() }

    val blurModifier = if (blurRenderEffect != null) {
        Modifier.graphicsLayer { renderEffect = blurRenderEffect }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .size(width = widthDp, height = heightDp)
            .then(blurModifier)
            .background(RecordChromeTokens.recordingFrameScrim),
    )
}
