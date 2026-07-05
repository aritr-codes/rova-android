package com.aritr.rova.ui.library.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.theme.GlassRole
import com.aritr.rova.ui.theme.GlassSurface
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.RovaIcons

/**
 * spec §9 — glass top bar. bento Task 7 chrome reflow: slots are back · title · search · select
 * (all optional; null = omit). Vault moved to [VaultDoorRow] in the timeline; density toggle and
 * sort left the top bar entirely (sort is pinned NEWEST — see [com.aritr.rova.ui.screens.HistoryViewModel]).
 */
@Composable
fun LibraryTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backLabel: String = "",
    onOpenSearch: (() -> Unit)? = null,
    searchLabel: String = "",
    onOpenSelect: (() -> Unit)? = null,
    selectLabel: String = "",
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
            if (onOpenSelect != null) {
                IconButton(onClick = onOpenSelect) {
                    SemanticIcon(
                        glyph = RovaIcons.Select,
                        contentDescription = selectLabel,
                        role = IconRole.Secondary,
                        modifier = Modifier.size(LibraryDimens.navIcon),
                    )
                }
            }
        }
    }
}
