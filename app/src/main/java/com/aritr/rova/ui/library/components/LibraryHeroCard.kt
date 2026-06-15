package com.aritr.rova.ui.library.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.library.LibraryRow

/**
 * spec §5.1 — hero card for the newest recording (polish pass: shorter focal media + a muted autoplay
 * preview + a unified filled action group). The media is one clickable (role Button, plays). When
 * [autoplay] and [previewUri] != null, the media is a looping muted preview; otherwise a static frame
 * (also the reduce-motion path — the caller passes autoplay=false). The quick-action group
 * (Play / Favorite / Share) never enters selection mode.
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
    previewUri: Uri? = null,
    autoplay: Boolean = false,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = LibraryDimens.screenPadH, vertical = 4.dp),
    ) {
        Text(
            eyebrow,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        val mediaModifier = Modifier
            .fillMaxWidth()
            .height(LibraryDimens.heroHeight)
            .clip(RoundedCornerShape(LibraryDimens.heroRadius))
            .clickable(onClick = onPlay)
            .semantics { role = Role.Button; contentDescription = playDescription }
        if (autoplay && previewUri != null) {
            LibraryAutoplayVideo(previewUri, thumbnail, mediaModifier)
        } else {
            VideoFrame(thumbnail, mediaModifier)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledIconButton(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = playDescription)
                }
                FilledTonalIconButton(onClick = onFavorite) {
                    if (row.favorite) {
                        Icon(Icons.Filled.Star, contentDescription = unfavoriteLabel)
                    } else {
                        Icon(Icons.Outlined.StarBorder, contentDescription = favoriteLabel)
                    }
                }
                FilledTonalIconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = shareLabel)
                }
            }
        }
    }
}
