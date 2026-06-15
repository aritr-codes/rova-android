package com.aritr.rova.ui.library.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
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
 * spec §5.1 — hero card for the newest recording. Polish P2/declutter (2026-06-15): **calm showcase**
 * model — the media fills the focal box and is the single accessible Play action; one quiet column of
 * session identity (eyebrow caps / large title / tabular meta) sits bottom-left over a tokenized scrim,
 * with generous breathing room. Favorite is the lone subordinate quick-action (top-right); Share and the
 * rest of the actions live in the floating item sheet (long-press, P8), so the hero is not a button
 * cluster. A subtle center Play glyph appears ONLY on the static poster — during [autoplay] the motion
 * is its own affordance, so no glyph competes with the moving frame. When [autoplay] and [previewUri] !=
 * null the media is a looping muted preview ([LibraryAutoplayVideo]); otherwise a static frame (also the
 * reduce-motion path). [onShare] is retained for the P8 item sheet wiring.
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
    val isPlayingPreview = autoplay && previewUri != null

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
        if (isPlayingPreview) {
            LibraryAutoplayVideo(previewUri, thumbnail, mediaModifier)
        } else {
            VideoFrame(thumbnail, mediaModifier)
        }

        // 2) Bottom caption scrim (tokenized) — draw-only, no semantics/clickable (won't steal hits).
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = LibraryDimens.heroScrimAlpha),
                    ),
                ),
        )

        // 3) The one subordinate quick-action — Favorite (ghost star, top-right). Share + everything else
        //    move to the floating item sheet (P8) so the hero is identity-first, not a button cluster.
        IconButton(
            onClick = onFavorite,
            modifier = Modifier.align(Alignment.TopEnd).padding(LibraryDimens.cardPadV),
        ) {
            if (row.favorite) {
                Icon(Icons.Filled.Star, contentDescription = unfavoriteLabel, tint = Color.White)
            } else {
                Icon(Icons.Outlined.StarBorder, contentDescription = favoriteLabel, tint = Color.White)
            }
        }

        // 4) Static-poster Play affordance — a quiet centered glyph, decorative (the media owns the one
        //    accessible Play). Hidden while autoplaying: moving frames are their own affordance.
        if (!isPlayingPreview) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.34f))
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }

        // 5) Session identity — eyebrow (Inter, tracked, caps) + large title + tabular meta, bottom-left
        //    with generous breathing room. This is the hero's focal content (distinct from compact grid).
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(LibraryDimens.captionPadH),
        ) {
            Text(eyebrow.uppercase(), style = RovaTokens.eyebrow, color = Color.White.copy(alpha = 0.85f))
            Text(
                row.title,
                style = MaterialTheme.typography.titleLarge,
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
    }
}
