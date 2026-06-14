package com.aritr.rova.ui.library.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.theme.GlassRole
import com.aritr.rova.ui.theme.GlassSurface

/**
 * spec §5.2 — glass contextual top bar shown while multi-select is active. Close (✕) + a live
 * "N selected" count (polite live-region, announced on change — anti-chant per §8) + select-all.
 * The [countLabel] is already formatted by the caller.
 */
@Composable
fun LibrarySelectionTopBar(
    countLabel: String,
    closeLabel: String,
    selectAllLabel: String,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
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
                    .padding(horizontal = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            IconButton(onClick = onSelectAll) {
                Icon(
                    Icons.Filled.SelectAll,
                    contentDescription = selectAllLabel,
                    modifier = Modifier.size(LibraryDimens.navIcon),
                )
            }
        }
    }
}
