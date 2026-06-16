package com.aritr.rova.ui.library.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.library.LibraryViewMode
import com.aritr.rova.ui.theme.GlassRole
import com.aritr.rova.ui.theme.GlassSurface

/** spec §9 — glass top bar with a grid/list toggle. The toggle icon shows the mode you'll switch TO. */
@Composable
fun LibraryTopBar(
    title: String,
    viewMode: LibraryViewMode,
    gridLabel: String,
    listLabel: String,
    onToggleView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(role = GlassRole.NavBar, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleView) {
                if (viewMode == LibraryViewMode.GRID) {
                    Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = listLabel)
                } else {
                    Icon(Icons.Filled.GridView, contentDescription = gridLabel)
                }
            }
        }
    }
}
