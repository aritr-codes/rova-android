package com.aritr.rova.ui.library.components

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.library.LibraryRow
import com.aritr.rova.ui.library.SmartTitle
import com.aritr.rova.ui.theme.GlassRole
import com.aritr.rova.ui.theme.GlassSurface

/**
 * spec §5.1 List mode — richer per-row layout on a glass Card. One clickable (role Button), merged label.
 * Slice 3 — in [isSelectionMode] tap toggles (caller routes [onClick]), long-press [onLongClick] enters;
 * a check indicator + [stateDescription] are added (§8).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryListRow(
    row: LibraryRow,
    thumbnail: Bitmap?,
    previewUri: android.net.Uri? = null,
    autoplay: Boolean = false,
    tileDescription: String,
    durationFallback: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: () -> Unit = {},
    selectedLabel: String = "",
    notSelectedLabel: String = "",
) {
    GlassSurface(
        role = GlassRole.Card,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LibraryDimens.screenPadH, vertical = LibraryDimens.cardPadV)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics {
                role = Role.Button
                contentDescription = tileDescription
                if (isSelectionMode) stateDescription = if (isSelected) selectedLabel else notSelectedLabel
            },
        shape = RoundedCornerShape(LibraryDimens.cardRadius),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isSelectionMode) {
                Icon(
                    imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            val thumbMod = Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(8.dp))
            if (autoplay && previewUri != null) {
                LibraryAutoplayVideo(previewUri, thumbnail, thumbMod)
            } else {
                VideoFrame(thumbnail, thumbMod)
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                val meta = if (row.durationMs > 0) SmartTitle.durationLabel(row.durationMs) else durationFallback
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
