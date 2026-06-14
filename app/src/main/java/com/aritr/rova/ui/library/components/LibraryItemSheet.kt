package com.aritr.rova.ui.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * spec §5.3 — per-item glass context sheet (overflow / long-press outside select mode):
 * Play · Share · Favorite · Rename · Move-to-Vault · View settings · Delete. No Export (ADR-0030).
 * The Vault row is hidden when [movable] is false (P+L). Each row is a Button-role clickable ≥48dp
 * (checkA11yClickableHasRole + checkA11yTargetSizeToken). All labels are passed in (en/es resources).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryItemSheet(
    isFavorite: Boolean,
    movable: Boolean,
    playLabel: String,
    shareLabel: String,
    favoriteLabel: String,
    unfavoriteLabel: String,
    renameLabel: String,
    vaultLabel: String,
    viewSettingsLabel: String,
    deleteLabel: String,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onMoveToVault: () -> Unit,
    onViewSettings: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetRow(Icons.Filled.PlayArrow, playLabel) { onPlay() }
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
