package com.aritr.rova.ui.library.components

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.library.LibraryRow

/**
 * spec §5.1 — hero card for the newest recording. The thumbnail is one clickable
 * (role Button, plays). The quick-action row (Play / Favorite / Share) does not
 * enter selection mode. Favorite toggles the sidecar via [onFavorite].
 */
@Composable
fun LibraryHeroCard(
    row: LibraryRow,
    thumbnail: Bitmap?,
    eyebrow: String,
    playDescription: String,
    favoriteLabel: String,
    unfavoriteLabel: String,
    shareLabel: String,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        VideoFrame(
            thumbnail,
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onPlay)
                .semantics { role = Role.Button; contentDescription = playDescription },
        )
        Text(row.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Row(Modifier.fillMaxWidth()) {
            IconButton(onClick = onPlay) {
                Icon(Icons.Filled.PlayArrow, contentDescription = playDescription)
            }
            IconButton(onClick = onFavorite) {
                if (row.favorite) {
                    Icon(Icons.Filled.Star, contentDescription = unfavoriteLabel)
                } else {
                    Icon(Icons.Outlined.StarBorder, contentDescription = favoriteLabel)
                }
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = shareLabel)
            }
        }
    }
}
