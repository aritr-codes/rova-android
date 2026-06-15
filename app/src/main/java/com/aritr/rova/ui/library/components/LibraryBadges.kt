package com.aritr.rova.ui.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.library.CaptionScrim
import com.aritr.rova.ui.library.LibraryBadge

private val scrimColor = Color(
    red = CaptionScrim.SCRIM_R / 255f,
    green = CaptionScrim.SCRIM_G / 255f,
    blue = CaptionScrim.SCRIM_B / 255f,
    alpha = CaptionScrim.SCRIM_ALPHA.toFloat(),
)
private val captionTextColor = Color(
    red = CaptionScrim.TEXT_R / 255f,
    green = CaptionScrim.TEXT_G / 255f,
    blue = CaptionScrim.TEXT_B / 255f,
    alpha = 1f,
)

/** Vertical scrim brush behind captions: transparent → AA scrim, so the frame isn't flatly darkened. */
private val captionScrimBrush = Brush.verticalGradient(listOf(Color.Transparent, scrimColor))

/** Small pill over a thumbnail, on a structural scrim (AA-guaranteed). Decorative — semantics cleared. */
@Composable
fun OverlayPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clearAndSetSemantics {}
            .background(scrimColor, RoundedCornerShape(LibraryDimens.pillRadius))
            .padding(horizontal = LibraryDimens.badgePadH, vertical = LibraryDimens.badgePadV),
    ) {
        Text(
            text = text,
            color = captionTextColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Localized label for an exceptional badge (null → no badge). Caller passes resource strings. */
@Composable
fun statusBadgeLabel(badge: LibraryBadge?, recovered: String, interrupted: String): String? = when (badge) {
    LibraryBadge.RECOVERED -> recovered
    LibraryBadge.INTERRUPTED -> interrupted
    null -> null
}

/**
 * Bottom caption over a thumbnail: a vertical gradient scrim (transparent → AA scrim) so text stays
 * readable over any frame without flatly dimming the image. Decorative — semantics cleared (merged on tile).
 */
@Composable
fun CaptionBar(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clearAndSetSemantics {}
            .background(captionScrimBrush)
            .padding(start = LibraryDimens.captionPadH, end = LibraryDimens.captionPadH, top = LibraryDimens.captionPadTop, bottom = LibraryDimens.captionPadBottom),
    ) {
        Text(
            text = text,
            color = captionTextColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
