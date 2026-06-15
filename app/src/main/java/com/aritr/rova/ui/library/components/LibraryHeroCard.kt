package com.aritr.rova.ui.library.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.ui.library.HeroMetaFormatter
import com.aritr.rova.ui.library.LibraryRow
import com.aritr.rova.ui.library.SmartTitle
import com.aritr.rova.ui.theme.RovaTokens

/**
 * spec §5.1 — hero card for the newest recording. Polish P2 (2026-06-15): **showcase overlay** model —
 * the media fills the focal box and carries the single accessible Play action; the eyebrow (Inter,
 * tracked, caps), title, and a tabular session-identity meta line are overlaid bottom-left over a
 * tokenized scrim; Favorite/Share are subordinate ghost icons top-right; one primary Play sits
 * bottom-right (a visual affordance — semantics cleared so TalkBack sees exactly one Play, the media,
 * which is also the focus-restore target). When [autoplay] and [previewUri] != null, the media is a
 * looping muted preview ([LibraryAutoplayVideo], which drops its static under-layer after the first
 * frame — the DualShot grey-strip fix); otherwise a static frame (also the reduce-motion path).
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
    mediaFocusRequester: FocusRequester? = null,
) {
    val clipCountLabel =
        if (row.clipCount > 0) pluralStringResource(R.plurals.library_hero_clip_count, row.clipCount, row.clipCount) else ""
    val durationLabel = if (row.durationMs > 0) SmartTitle.durationLabel(row.durationMs) else ""
    val metaLine = HeroMetaFormatter.format(row.dateLabel, "", clipCountLabel, durationLabel)

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = LibraryDimens.screenPadH, vertical = LibraryDimens.cardPadV)
            .height(LibraryDimens.heroHeight)
            .clip(RoundedCornerShape(LibraryDimens.heroRadius))
            .border(
                width = LibraryDimens.cardEdgeWidth,
                color = Color.White.copy(alpha = LibraryDimens.dividerAlpha),
                shape = RoundedCornerShape(LibraryDimens.heroRadius),
            ),
    ) {
        // 1) Media fills the box and is the SINGLE accessible Play target (also the focus-restore node).
        val mediaModifier = Modifier
            .matchParentSize()
            .then(if (mediaFocusRequester != null) Modifier.focusRequester(mediaFocusRequester) else Modifier)
            .clickable(onClick = onPlay)
            .semantics { role = Role.Button; contentDescription = playDescription }
        if (autoplay && previewUri != null) {
            LibraryAutoplayVideo(previewUri, thumbnail, mediaModifier)
        } else {
            VideoFrame(thumbnail, mediaModifier)
        }

        // 2) Bottom caption scrim (tokenized) — draw-only, no semantics/clickable (won't steal hits).
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .fillMaxHeight(0.55f)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = LibraryDimens.heroScrimAlpha),
                    ),
                ),
        )

        // 3) Subordinate actions — ghost icons, top-right (real accessible controls).
        Row(
            Modifier.align(Alignment.TopEnd).padding(LibraryDimens.cardPadV),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onFavorite) {
                if (row.favorite) {
                    Icon(Icons.Filled.Star, contentDescription = unfavoriteLabel, tint = Color.White)
                } else {
                    Icon(Icons.Outlined.StarBorder, contentDescription = favoriteLabel, tint = Color.White)
                }
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = shareLabel, tint = Color.White)
            }
        }

        // 4) Caption block — eyebrow (Inter, tracked, caps) + title + tabular meta, overlaid bottom-left.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(LibraryDimens.captionPadH),
        ) {
            Text(eyebrow.uppercase(), style = RovaTokens.eyebrow, color = Color.White)
            Text(
                row.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (metaLine.isNotBlank()) {
                Text(
                    metaLine,
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 5) Primary CTA — single filled Play, bottom-right. Decorative (semantics cleared): the
        //    accessible Play lives once on the media (1). Still calls onPlay on touch.
        FilledIconButton(
            onClick = onPlay,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(LibraryDimens.captionPadH)
                .clearAndSetSemantics {},
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
        }
    }
}
