package com.aritr.rova.ui.library.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.library.LibraryViewMode
import com.aritr.rova.ui.theme.GlassRole
import com.aritr.rova.ui.theme.GlassSurface
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.RovaIcons

/**
 * spec §9 — glass top bar with a grid/list toggle. The toggle icon shows the mode you'll switch TO.
 * Slice 3 — optional back nav + vault entry (the route surface needs both; null = omit).
 */
@Composable
fun LibraryTopBar(
    title: String,
    viewMode: LibraryViewMode,
    gridLabel: String,
    listLabel: String,
    onToggleView: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backLabel: String = "",
    onOpenVault: (() -> Unit)? = null,
    vaultLabel: String = "",
    onOpenSearch: (() -> Unit)? = null,
    searchLabel: String = "",
    onOpenSort: (() -> Unit)? = null,
    sortLabel: String = "",
) {
    GlassSurface(role = GlassRole.NavBar, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = LibraryDimens.topBarPadV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = backLabel,
                        modifier = Modifier.size(LibraryDimens.navIcon),
                    )
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(horizontal = if (onBack != null) 4.dp else 12.dp),
            )
            if (onOpenVault != null) {
                IconButton(onClick = onOpenVault) {
                    SemanticIcon(
                        glyph = RovaIcons.Vault,
                        contentDescription = vaultLabel,
                        role = IconRole.Secondary,
                        modifier = Modifier.size(LibraryDimens.navIcon),
                    )
                }
            }
            if (onOpenSearch != null) {
                IconButton(onClick = onOpenSearch) {
                    SemanticIcon(
                        glyph = RovaIcons.Search,
                        contentDescription = searchLabel,
                        role = IconRole.Secondary,
                        modifier = Modifier.size(LibraryDimens.navIcon),
                    )
                }
            }
            if (onOpenSort != null) {
                IconButton(onClick = onOpenSort) {
                    SemanticIcon(
                        glyph = RovaIcons.Sort,
                        contentDescription = sortLabel,
                        role = IconRole.Secondary,
                        modifier = Modifier.size(LibraryDimens.navIcon),
                    )
                }
            }
            IconButton(onClick = onToggleView) {
                if (viewMode == LibraryViewMode.GRID) {
                    Icon(
                        Icons.AutoMirrored.Filled.ViewList,
                        contentDescription = listLabel,
                        modifier = Modifier.size(LibraryDimens.navIcon),
                    )
                } else {
                    SemanticIcon(
                        glyph = RovaIcons.View,
                        contentDescription = gridLabel,
                        role = IconRole.Secondary,
                        modifier = Modifier.size(LibraryDimens.navIcon),
                    )
                }
            }
        }
    }
}
