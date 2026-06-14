package com.aritr.rova.ui.library.components

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.ui.library.LibraryRow
import com.aritr.rova.ui.library.SmartTitle

/**
 * spec §5.1 — opaque 2-column grid tile. Thumbnail plane + mandatory duration pill
 * (hidden at 0 for legacy rows) + exceptional badge + P+L pill + caption. One
 * clickable (role Button) carrying the merged [tileDescription]; badges are
 * decorative. Optional [itemSemantics] adds grid collectionItemInfo (Task 14).
 *
 * Slice 3 — in [isSelectionMode] the tile is a toggle: tap toggles selection (caller routes [onClick]
 * accordingly), long-press [onLongClick] enters select mode. A selection ring + [stateDescription]
 * ("selected"/"not selected", §8) are added so TalkBack drag-select is not invisible.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryGridCard(
    row: LibraryRow,
    thumbnail: Bitmap?,
    tileDescription: String,
    statusLabel: String?,
    plLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    itemSemantics: (SemanticsPropertyReceiver.() -> Unit)? = null,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: () -> Unit = {},
    selectedLabel: String = "",
    notSelectedLabel: String = "",
) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics {
                role = Role.Button
                contentDescription = tileDescription
                if (isSelectionMode) stateDescription = if (isSelected) selectedLabel else notSelectedLabel
                itemSemantics?.invoke(this)
            },
    ) {
        VideoFrame(thumbnail, Modifier.fillMaxSize())
        if (statusLabel != null) {
            OverlayPill(statusLabel, Modifier.align(Alignment.TopStart).padding(6.dp))
        }
        if (row.topology == CaptureTopology.DualShot) {
            OverlayPill(plLabel, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        if (row.durationMs > 0L) {
            OverlayPill(
                SmartTitle.durationLabel(row.durationMs),
                Modifier.align(Alignment.BottomEnd).padding(6.dp),
            )
        }
        CaptionBar(
            row.title,
            Modifier.align(Alignment.BottomStart).fillMaxWidth(),
        )
        if (isSelectionMode) {
            // Selection scrim + check indicator (decorative — state is on the merged tile node).
            if (isSelected) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                )
            }
            Icon(
                imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            )
        }
    }
}
