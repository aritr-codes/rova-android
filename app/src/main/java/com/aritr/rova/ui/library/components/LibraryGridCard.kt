package com.aritr.rova.ui.library.components

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.RovaIcons
import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.ui.components.RovaAnimations.pressScale
import com.aritr.rova.ui.library.LibraryRow
import com.aritr.rova.ui.library.SmartTitle
import com.aritr.rova.ui.library.rememberLibraryColors

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
    previewUri: android.net.Uri? = null,
    autoplay: Boolean = false,
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
    val shape = RoundedCornerShape(LibraryDimens.cardRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val libraryColors = rememberLibraryColors()
    Box(
        modifier
            // Record-consistent press feedback on the tile surface (inside the gutter padding the caller
            // applied) — reduce-motion gated in RovaAnimations.pressScale.
            .pressScale(interactionSource)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(shape)
            // Permanent glass-consistent hairline frame (matches the glass surfaces' 1dp white edge —
            // NOT glass/blur over the thumbnail, spec §9).
            .border(LibraryDimens.cardEdgeWidth, libraryColors.cardEdge, shape)
            // Selection: soft glass-consistent ring (replaces the hard 2dp primary border). The check
            // chip is the authoritative selected-state carrier; this ring reinforces.
            .then(
                if (isSelected) {
                    Modifier.border(
                        LibraryDimens.selectionEdgeWidth,
                        libraryColors.selectionRing,
                        shape,
                    )
                } else {
                    Modifier
                },
            )
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
                itemSemantics?.invoke(this)
            },
    ) {
        if (autoplay && previewUri != null) {
            LibraryAutoplayVideo(previewUri, thumbnail, Modifier.fillMaxSize())
        } else {
            VideoFrame(thumbnail, Modifier.fillMaxSize())
        }
        // Session-identity overlays only outside selection mode (selection de-clutters — Photos/Gallery
        // convention): clip-count chip (TopStart, >1), status + P+L (TopEnd), duration badge (BottomEnd).
        if (!isSelectionMode) {
            if (row.clipCount > 1) {
                OverlayPill(
                    pluralStringResource(R.plurals.library_hero_clip_count, row.clipCount, row.clipCount),
                    Modifier.align(Alignment.TopStart).padding(6.dp),
                )
            }
            // Standard recording → orientation glyph only; DualShot → "DualShot" label + glyph (the
            // label is the special-mode indicator, orientation is the secondary cue). Owner request.
            Row(
                Modifier.align(Alignment.TopEnd).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (statusLabel != null) OverlayPill(statusLabel)
                // M3a was REVERTED (2026-06-16): a DualShot session fans into per-SIDE rows, so the
                // orientation glyph is the side's authoritative identity (OrientationResolver §1) — the
                // one cue distinguishing the two side-tiles of a session — NOT an ambiguous "both
                // framings" badge. Dropping it lost real info AND diverged from LibraryListRow, which
                // keeps it. The only crowding (status + DualShot + orientation) is the rare exceptional-
                // badge case, not worth destroying side identity. Keep both pills.
                if (row.topology == CaptureTopology.DualShot) OverlayPill(plLabel)
                row.orientation?.let { OrientationFramePill(it) }
            }
            if (row.durationMs > 0) {
                OverlayPill(
                    SmartTitle.durationLabel(row.durationMs),
                    Modifier.align(Alignment.BottomEnd).padding(6.dp),
                )
            }
        }
        // Caption = the session title over a gradient scrim (bottom-start; duration badge sits at the
        // bottom-end). The title already encodes the session via SmartTitle.
        CaptionBar(row.title, Modifier.align(Alignment.BottomStart).fillMaxWidth())
        if (isSelectionMode) {
            // Lighter selection (Photos/Gallery): keep the frame bright, lean on a ring + filled check on a
            // small contrasting chip. State lives on the merged tile node — these are decorative.
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(LibraryDimens.cardPadV)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(libraryColors.checkChipScrim),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    SemanticIcon(
                        glyph = RovaIcons.Select,
                        contentDescription = null,
                        role = IconRole.Accent,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = libraryColors.overlayText,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
