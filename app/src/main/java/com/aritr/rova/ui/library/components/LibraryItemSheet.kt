package com.aritr.rova.ui.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.library.rememberLibraryColors
import com.aritr.rova.ui.theme.FloatingGlassSheet
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.RovaGlyph
import com.aritr.rova.ui.theme.RovaIcons

/**
 * spec §5.3 / Polish P8 — per-item context sheet (overflow / long-press outside select mode):
 * Play · Share · Favorite · Rename · View settings · Move-to-Vault · Delete. No Export (ADR-0030).
 * When [movable] is false (DualShot), the Vault row is shown DISABLED with [vaultUnavailableReason]
 * (greyed, not hidden — owner 2026-06-17) rather than dropped. Each row is a Button-role clickable
 * ≥44dp (checkA11yClickableHasRole + checkA11yTargetSizeToken). All labels are passed in (en/es).
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
    vaultUnavailableReason: String?,
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
    val libraryColors = rememberLibraryColors()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent, // we paint our own floating card
        contentWindowInsets = { WindowInsets(0) }, // we handle insets ourselves
        dragHandle = null, // floating card → no attached handle
        shape = RectangleShape, // visible rounding is on the inner Surface
    ) {
        // M2 — a floating glass card (palette-tinted, role=BottomSheet) that still casts a real shadow.
        FloatingGlassSheet(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally) // center the capped card on wide screens
                .widthIn(max = LibraryDimens.sheetMaxWidth)
                .padding(horizontal = LibraryDimens.sheetEdgeGap) // gives the drop shadow room
                .navigationBarsPadding() // never collide with the system nav bar (gesture AND 3-button)
                .padding(bottom = LibraryDimens.sheetEdgeGap),
            shape = RoundedCornerShape(LibraryDimens.sheetCornerRadius),
            shadowElevation = LibraryDimens.sheetElevation,
        ) {
            // Compact pass (owner 2026-06-17): no drag-handle (floating card, redundant), tighter
            // padding + slimmer header + 44dp rows so the sheet sits well under half-screen.
            Column(Modifier.padding(vertical = 4.dp)) {
                SheetHeader(headerThumbnail, headerTitle, headerMeta)
                HorizontalDivider(color = libraryColors.cardEdge)
                // UX-B — grouped by intent: Primary (do-the-thing) · Secondary (manage) · Danger (destructive),
                // separated by hairlines so Delete is visually quarantined from the safe actions.
                // ── Primary ──
                SheetRow(Icons.Filled.PlayArrow, playLabel) { onPlay() }
                SheetRow(Icons.Filled.Share, shareLabel) { onShare() }
                HorizontalDivider(color = libraryColors.cardEdge)
                // ── Secondary ──
                SheetRow(Icons.Filled.Checklist, selectLabel) { onSelect() }
                SheetRow(
                    if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    if (isFavorite) unfavoriteLabel else favoriteLabel,
                ) { onToggleFavorite() }
                SheetRow(Icons.Filled.Edit, renameLabel) { onRename() }
                SheetRow(RovaIcons.Details.glyph, viewSettingsLabel) { onViewSettings() }
                SheetRow(
                    glyph = RovaIcons.Vault,
                    label = vaultLabel,
                    enabled = movable,
                    reason = vaultUnavailableReason,
                ) { onMoveToVault() }
                HorizontalDivider(color = libraryColors.cardEdge)
                // ── Danger ──
                SheetRow(Icons.Filled.Delete, deleteLabel, danger = true) { onDelete() }
            }
        }
    }
}

/** P8 session-identity header — thumbnail + title + tabular meta so the sheet reads as a SESSION. */
@Composable
private fun SheetHeader(thumbnail: android.graphics.Bitmap?, title: String, meta: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoFrame(
            thumbnail,
            Modifier.size(width = 48.dp, height = 30.dp).clip(RoundedCornerShape(8.dp)),
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
private fun SheetRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    // Explicit content colour: the floating sheet is a GlassSurface (no Material Surface to seed
    // LocalContentColor), so rows must state their own colour. Danger (Delete) reads in the error tint.
    val contentColor =
        if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor)
        Spacer(Modifier.width(20.dp))
        Text(label, color = contentColor)
    }
}

/**
 * Bespoke-glyph row (ADR-0031): renders a two-layer [RovaGlyph] through the SemanticIcon seam.
 * When [enabled] is false the row is non-clickable, greyed, and shows [reason] as a subtext line
 * (e.g. DualShot can't be vaulted — owner 2026-06-17); it still announces as a disabled Button.
 */
@Composable
private fun SheetRow(
    glyph: RovaGlyph,
    label: String,
    enabled: Boolean = true,
    reason: String? = null,
    onClick: () -> Unit,
) {
    val baseColor = MaterialTheme.colorScheme.onSurface
    val contentColor = if (enabled) baseColor else baseColor.copy(alpha = 0.38f)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics {
                role = Role.Button
                if (!enabled) disabled()
            }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        SemanticIcon(
            glyph = glyph,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            role = if (enabled) IconRole.Default else IconRole.Disabled,
        )
        Spacer(Modifier.width(20.dp))
        Column {
            Text(label, color = contentColor)
            if (!enabled && reason != null) {
                Text(reason, style = MaterialTheme.typography.bodySmall, color = contentColor)
            }
        }
    }
}
