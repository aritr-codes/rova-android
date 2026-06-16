package com.aritr.rova.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Theme Foundation (M2, 2026-06-16) — an elevated, *floating* glass surface for modal sheets/cards that
 * need a real cast shadow (e.g. the Library item sheet). [GlassSurface] is deliberately glass-only and
 * casts no shadow, so this thin wrapper adds the drop shadow OUTSIDE the glass (`clip = false`) and lets
 * the inner [GlassSurface] paint the role-resolved, palette-tinted fill + edge. The sheet therefore joins
 * the active theme identity (via [GlassRole.BottomSheet]) while keeping its floating shadow.
 *
 * Not a new role — reuses the existing [GlassRole.BottomSheet], which [GlassResolver] resolves to a
 * near-opaque palette-tinted, unblurred fill so content underneath never bleeds through.
 */
@Composable
fun FloatingGlassSheet(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    shadowElevation: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    Box(modifier.shadow(elevation = shadowElevation, shape = shape, clip = false)) {
        GlassSurface(role = GlassRole.BottomSheet, shape = shape, content = content)
    }
}
