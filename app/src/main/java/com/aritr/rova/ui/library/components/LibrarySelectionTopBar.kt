package com.aritr.rova.ui.library.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.theme.GlassRole
import com.aritr.rova.ui.theme.GlassSurface
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.RovaIcons

/**
 * bento Task 9 — frozen selection top bar (replaces the old close/count/select-all bar AND the
 * bottom [LibraryBatchBar], which this absorbs): close · count(live) · All · info · favorite ·
 * vault · delete. [infoEnabled] is true iff exactly one row is selected (the details sheet needs a
 * single subject); [vaultEnabled] is false when the selection contains a DualShot session
 * (ADR-0025 — not vault-movable). Each icon action is an `IconButton` (≥48dp target,
 * Button-role/disabled semantics built in) — checkA11yClickableHasRole / checkA11yTargetSizeToken.
 */
@Composable
fun LibrarySelectionTopBar(
    countLabel: String,
    closeLabel: String,
    selectAllLabel: String,
    infoLabel: String,
    favoriteLabel: String,
    vaultLabel: String,
    vaultDisabledLabel: String,
    deleteLabel: String,
    infoEnabled: Boolean,
    vaultEnabled: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onInfo: () -> Unit,
    onFavorite: () -> Unit,
    onVault: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(role = GlassRole.NavBar, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = LibraryDimens.topBarPadV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = closeLabel,
                    modifier = Modifier.size(LibraryDimens.navIcon),
                )
            }
            Text(
                text = countLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onSelectAll) { Text(selectAllLabel) }
            IconButton(onClick = onInfo, enabled = infoEnabled) {
                SemanticIcon(
                    glyph = RovaIcons.Info,
                    contentDescription = infoLabel,
                    modifier = Modifier.size(LibraryDimens.navIcon),
                    role = if (infoEnabled) IconRole.Default else IconRole.Disabled,
                )
            }
            IconButton(onClick = onFavorite) {
                SemanticIcon(
                    glyph = RovaIcons.FavoriteOn,
                    contentDescription = favoriteLabel,
                    modifier = Modifier.size(LibraryDimens.navIcon),
                )
            }
            IconButton(onClick = onVault, enabled = vaultEnabled) {
                SemanticIcon(
                    glyph = RovaIcons.Vault,
                    contentDescription = if (vaultEnabled) vaultLabel else vaultDisabledLabel,
                    modifier = Modifier.size(LibraryDimens.navIcon),
                    role = if (vaultEnabled) IconRole.Default else IconRole.Disabled,
                )
            }
            IconButton(onClick = onDelete) {
                SemanticIcon(
                    glyph = RovaIcons.Delete,
                    contentDescription = deleteLabel,
                    modifier = Modifier.size(LibraryDimens.navIcon),
                )
            }
        }
    }
}
