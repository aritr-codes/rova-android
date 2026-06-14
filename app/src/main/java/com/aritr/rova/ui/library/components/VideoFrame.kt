package com.aritr.rova.ui.library.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * Opaque keyframe plane for a Library card (spec §9 — never glass-on-thumbnail).
 * Center-crops the [bitmap]; a null bitmap (metadata still loading / legacy row)
 * shows a flat surfaceVariant placeholder. The frame is decorative — its
 * semantics are cleared so the merged tile contentDescription is the only label.
 */
@Composable
fun VideoFrame(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clearAndSetSemantics {},
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
