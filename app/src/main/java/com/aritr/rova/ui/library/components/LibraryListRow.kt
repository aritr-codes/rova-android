package com.aritr.rova.ui.library.components

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.ui.components.RovaAnimations.pressScale
import com.aritr.rova.ui.library.LibraryBadge
import com.aritr.rova.ui.library.LibraryRow
import com.aritr.rova.ui.library.SessionCaption
import com.aritr.rova.ui.library.SmartTitle
import com.aritr.rova.ui.library.StorageFormat
import com.aritr.rova.ui.theme.GlassRole
import com.aritr.rova.ui.theme.GlassSurface
import java.util.Locale

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
    dualShotLabel: String = "",
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: () -> Unit = {},
    selectedLabel: String = "",
    notSelectedLabel: String = "",
) {
    val interactionSource = remember { MutableInteractionSource() }
    GlassSurface(
        role = GlassRole.Card,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LibraryDimens.screenPadH, vertical = LibraryDimens.cardPadV)
            .pressScale(interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick,
            )
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
            // Larger, more prominent thumbnail (112×63 = exact 16:9) with a duration badge overlay.
            Box(Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(LibraryDimens.pillRadius))) {
                if (autoplay && previewUri != null) {
                    LibraryAutoplayVideo(previewUri, thumbnail, Modifier.fillMaxSize())
                } else {
                    VideoFrame(thumbnail, Modifier.fillMaxSize())
                }
                // Standard → orientation glyph only; DualShot → "DualShot" label + glyph (owner request).
                Row(
                    Modifier.align(Alignment.TopStart).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (row.topology == CaptureTopology.DualShot) OverlayPill(dualShotLabel)
                    row.orientation?.let { OrientationFramePill(it) }
                }
                if (row.durationMs > 0) {
                    OverlayPill(
                        SmartTitle.durationLabel(row.durationMs),
                        Modifier.align(Alignment.BottomEnd).padding(4.dp),
                    )
                }
            }
            Column(Modifier.padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    statusDotColor(row.badge)?.let { dot ->
                        Box(
                            Modifier
                                .padding(end = 6.dp)
                                .size(LibraryDimens.statusDotSize)
                                .clip(CircleShape)
                                .background(dot),
                        )
                    }
                    Text(
                        row.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                val clipLabel =
                    if (row.clipCount > 1) pluralStringResource(R.plurals.library_hero_clip_count, row.clipCount, row.clipCount) else ""
                val durationLabel = if (row.durationMs > 0) SmartTitle.durationLabel(row.durationMs) else durationFallback
                val meta = SessionCaption.listMeta(
                    clipCountLabel = clipLabel,
                    durationLabel = durationLabel,
                    sizeLabel = StorageFormat.size(row.sizeBytes, Locale.getDefault()),
                )
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Status dot tint for the session row — quiet at-a-glance state without opening (the badge label is
 * still carried by the merged tile semantics, so colour is not the only signal). null = no dot.
 */
private fun statusDotColor(badge: LibraryBadge?): Color? = when (badge) {
    LibraryBadge.RECOVERED -> Color(0xFF6FE3A1)
    LibraryBadge.INTERRUPTED -> Color(0xFFE3B566)
    null -> null
}
