package com.aritr.rova.ui.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.theme.GlassRole
import com.aritr.rova.ui.theme.GlassSurface

/**
 * spec §5.2 — glass bottom batch bar: Share · Vault · Favorite · Delete (no Export, ADR-0030). Vault is
 * disabled (with a reason via contentDescription) when the selection has no movable session
 * ([vaultEnabled] == false; P+L not movable). IconButtons carry the Button role + a contentDescription,
 * satisfying checkA11yClickableHasRole and the ≥24dp target (IconButton is 48dp).
 */
@Composable
fun LibraryBatchBar(
    shareLabel: String,
    vaultLabel: String,
    vaultDisabledLabel: String,
    favoriteLabel: String,
    deleteLabel: String,
    vaultEnabled: Boolean,
    onShare: () -> Unit,
    onVault: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(role = GlassRole.NavBar, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = shareLabel) }
            IconButton(onClick = onVault, enabled = vaultEnabled) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = if (vaultEnabled) vaultLabel else vaultDisabledLabel,
                )
            }
            IconButton(onClick = onFavorite) { Icon(Icons.Filled.Star, contentDescription = favoriteLabel) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = deleteLabel) }
        }
    }
}
