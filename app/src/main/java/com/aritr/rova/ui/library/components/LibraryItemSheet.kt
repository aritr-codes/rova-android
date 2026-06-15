package com.aritr.rova.ui.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * spec §5.3 / Polish P8 — per-item context sheet (overflow / long-press outside select mode):
 * Play · Share · Favorite · Rename · Move-to-Vault · View settings · Delete. No Export (ADR-0030).
 * The Vault row is hidden when [movable] is false (DualShot). Each row is a Button-role clickable ≥48dp
 * (checkA11yClickableHasRole + checkA11yTargetSizeToken). All labels are passed in (en/es resources).
 *
 * P8 — re-skinned from a hard-attached bottom sheet into a **floating "Brave-style" elevated card**: the
 * [ModalBottomSheet] container is transparent and inset-free, and an inner elevated [Surface] floats off
 * the screen edges with a gap, fully-rounded corners, a cast shadow, and a nav-bar-inset margin so it
 * never collides with the system bar on gesture OR 3-button nav. A session-identity header (thumbnail +
 * title + meta) leads the card. A plain elevated `Surface` (NOT `GlassSurface`) is used deliberately — a
 * floating card needs a real drop shadow ([sheetElevation]); glass has no cast shadow. The swipe-to-
 * dismiss, slide-up animation, and scrim all still live on `ModalBottomSheet`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryItemSheet(
    isFavorite: Boolean,
    movable: Boolean,
    headerTitle: String,
    headerMeta: String,
    headerThumbnail: android.graphics.Bitmap?,
    playLabel: String,
    selectLabel: String,
    shareLabel: String,
    favoriteLabel: String,
    unfavoriteLabel: String,
    renameLabel: String,
    vaultLabel: String,
    viewSettingsLabel: String,
    deleteLabel: String,
    onPlay: () -> Unit,
    onSelect: () -> Unit,
    onShare: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onMoveToVault: () -> Unit,
    onViewSettings: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent, // we paint our own floating card
        contentWindowInsets = { WindowInsets(0) }, // we handle insets ourselves
        dragHandle = null, // floating card → no attached handle
        shape = RectangleShape, // visible rounding is on the inner Surface
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally) // center the capped card on wide screens
                .widthIn(max = LibraryDimens.sheetMaxWidth)
                .padding(horizontal = LibraryDimens.sheetEdgeGap) // gives the drop shadow room
                .navigationBarsPadding() // never collide with the system nav bar (gesture AND 3-button)
                .padding(bottom = LibraryDimens.sheetEdgeGap),
            shape = RoundedCornerShape(LibraryDimens.sheetCornerRadius),
            shadowElevation = LibraryDimens.sheetElevation,
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Box(
                    Modifier
                        .padding(top = 4.dp, bottom = 8.dp)
                        .align(Alignment.CenterHorizontally)
                        .size(width = 34.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)),
                )
                SheetHeader(headerThumbnail, headerTitle, headerMeta)
                HorizontalDivider(color = Color.White.copy(alpha = LibraryDimens.dividerAlpha))
                SheetRow(Icons.Filled.PlayArrow, playLabel) { onPlay() }
                SheetRow(Icons.Filled.Checklist, selectLabel) { onSelect() }
                SheetRow(Icons.Filled.Share, shareLabel) { onShare() }
                SheetRow(
                    if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    if (isFavorite) unfavoriteLabel else favoriteLabel,
                ) { onToggleFavorite() }
                SheetRow(Icons.Filled.Edit, renameLabel) { onRename() }
                if (movable) SheetRow(Icons.Filled.Lock, vaultLabel) { onMoveToVault() }
                SheetRow(Icons.Filled.Settings, viewSettingsLabel) { onViewSettings() }
                SheetRow(Icons.Filled.Delete, deleteLabel) { onDelete() }
            }
        }
    }
}

/** P8 session-identity header — thumbnail + title + tabular meta so the sheet reads as a SESSION. */
@Composable
private fun SheetHeader(thumbnail: android.graphics.Bitmap?, title: String, meta: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoFrame(
            thumbnail,
            Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(9.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(20.dp))
        Text(label)
    }
}
