// app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt
package com.aritr.rova.ui.screens

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
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
import androidx.compose.ui.graphics.Color
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
    Box(modifier = modifier.background(RecordChromeTokens.camZoneBackground)) {
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
