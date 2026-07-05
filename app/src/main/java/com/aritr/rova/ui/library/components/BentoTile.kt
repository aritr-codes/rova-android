package com.aritr.rova.ui.library.components

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.aritr.rova.R
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.components.rememberReduceMotion
import com.aritr.rova.ui.library.LibraryOrientation
import com.aritr.rova.ui.library.LibraryRow
import com.aritr.rova.ui.library.LibrarySessionSide
import com.aritr.rova.ui.library.PressFeedback
import com.aritr.rova.ui.library.SmartTitle
import com.aritr.rova.ui.library.TileSemantics
import com.aritr.rova.ui.library.rememberLibraryColors
import com.aritr.rova.ui.theme.IconStatus
import com.aritr.rova.ui.theme.RovaIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val SEAM_COLOR = Color(0xFF060308)
private val META_PILL_BG = Color(0x9E060308)
private val FAVORITE_DOT_BG = Color(0x9E060308)
private val SELECTED_DIM = Color(0x59060308)
private val TILE_RADIUS = 16.dp

/**
 * Bento single-tile / DualShot-diptych anatomy (frozen, spec 2026-07-04 / Task 4 — transcribe, do not
 * restyle). Dual predicate is `row.sides.size == 2` (codex-reconciled): a DualShot session with only one
 * surviving side falls back to a single tile using [LibraryRow.side]/[LibraryRow.orientation] — the frozen
 * spec has no one-pane diptych, and [LibrarySessionSide] index 1 is never read without the size check.
 *
 * Media (thumbnail over placeholder gradient + press/selection scale + ring) lives INSIDE the seam/panes;
 * the meta pill, LATEST/status chip, favorite dot, and selection check are TILE-level overlays that span
 * the whole card (including across the diptych seam), matching the frozen anatomy where overlays sit on
 * the outer container, not inside either button. Thumbnails are supplied by [thumbnailFor] (stableKey ->
 * decoded bitmap); a null bitmap falls back to the placeholder gradient ([VideoFrame] draws nothing over it).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BentoTile(
    row: LibraryRow,
    heightDp: Int,
    span: Int,
    isLatest: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onPlay: (sideKey: String?) -> Unit,
    onToggleSelect: () -> Unit,
    onEnterSelection: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailFor: (String) -> Bitmap? = { null },
) {
    val colors = rememberLibraryColors()
    val reduceMotion = rememberReduceMotion()
    val isDual = row.sides.size == 2
    val wide = span >= 3

    val timeLabel = remember(row.dateMillis) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(row.dateMillis))
    }
    val durationLabel = if (wide) SmartTitle.durationLabel(row.durationMs) else ""

    val portraitWord = stringResource(R.string.library_orientation_portrait)
    val landscapeWord = stringResource(R.string.library_orientation_landscape)
    fun orientationWord(o: LibraryOrientation?) = when (o) {
        LibraryOrientation.PORTRAIT -> portraitWord
        LibraryOrientation.LANDSCAPE -> landscapeWord
        null -> null
    }

    val recoveredLabel = stringResource(R.string.library_badge_recovered)
    val interruptedLabel = stringResource(R.string.library_badge_interrupted)
    val autoStoppedLabel = stringResource(R.string.library_badge_auto_stopped)
    val statusLabel = statusBadgeLabel(row.badge, recoveredLabel, interruptedLabel, autoStoppedLabel)

    Box(modifier.height(heightDp.dp)) {
        if (isDual) {
            // Aggregator populates `sides` portrait-first already; sort defensively so the frozen
            // "Portrait ALWAYS the left pane" invariant holds even if that upstream contract changes.
            val ordered = row.sides.sortedBy { if (it.side == VideoSide.PORTRAIT) 0 else 1 }
            val groupLabel = String.format(
                stringResource(R.string.library_a11y_dualshot_group),
                TileSemantics.bentoLabel(selecting, null, timeLabel, SmartTitle.durationLabel(row.durationMs), row.favorite, isLatest),
            )
            Row(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(TILE_RADIUS))
                    .background(SEAM_COLOR)
                    .then(selectionRing(selected, colors.accentFill))
                    .semantics {
                        contentDescription = groupLabel
                        isTraversalGroup = true
                    },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ordered.forEach { side ->
                    val sideOrientation = if (side.side == VideoSide.PORTRAIT) LibraryOrientation.PORTRAIT else LibraryOrientation.LANDSCAPE
                    val sideWord = orientationWord(sideOrientation) ?: ""
                    val paneLabel = TileSemantics.bentoPaneLabel(selecting, sideWord, timeLabel, SmartTitle.durationLabel(side.durationMs))
                    BentoPane(
                        selected = selected,
                        reduceMotion = reduceMotion,
                        label = paneLabel,
                        bitmap = thumbnailFor(side.stableKey),
                        onTap = { if (selecting) onToggleSelect() else onPlay(side.side.name) },
                        onLongPress = { onEnterSelection(); onToggleSelect() },
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                }
            }
        } else {
            val label = TileSemantics.bentoLabel(selecting, orientationWord(row.orientation), timeLabel, SmartTitle.durationLabel(row.durationMs), row.favorite, isLatest)
            BentoPane(
                selected = selected,
                reduceMotion = reduceMotion,
                label = label,
                bitmap = thumbnailFor(row.stableKey),
                onTap = { if (selecting) onToggleSelect() else onPlay(null) },
                onLongPress = { onEnterSelection(); onToggleSelect() },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(TILE_RADIUS))
                    .then(selectionRing(selected, colors.accentFill)),
            )
        }

        // ── Tile-level overlays (span the whole card, across the seam) ──
        if (row.badge != null && statusLabel != null) {
            StatusBadgePill(
                badge = row.badge,
                text = statusLabel,
                stopReason = row.badgeStopReason,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            )
        } else if (isLatest) {
            LatestChip(Modifier.align(Alignment.TopStart).padding(8.dp))
        }

        MetaPill(
            orientation = if (isDual) null else row.orientation,
            dual = isDual,
            timeLabel = timeLabel,
            durationLabel = durationLabel,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        )

        if (row.favorite && !selecting) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .clearAndSetSemantics {}
                    .background(FAVORITE_DOT_BG, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) {
                SemanticIcon(
                    glyph = RovaIcons.FavoriteOn,
                    contentDescription = null,
                    status = IconStatus.Warning,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        if (selecting) {
            SelectionCheck(
                selected = selected,
                accentFill = colors.accentFill,
                accentInk = colors.accentInk,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            )
        }
    }
}

/** Selected-state ring (2dp accentFill) vs the resting hairline (1dp White@0.08) — frozen anatomy. */
private fun selectionRing(selected: Boolean, accentFill: Color): Modifier =
    if (selected) {
        Modifier.border(2.dp, accentFill, RoundedCornerShape(TILE_RADIUS))
    } else {
        Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(TILE_RADIUS))
    }

/**
 * One media pane — the single tile's whole body, or one half of a DualShot diptych. A 140°-angled
 * surfaceContainerHigh→surface gradient placeholder, the decoded [bitmap] thumbnail layered above it
 * (null shows the gradient — [VideoFrame] draws nothing over a null bitmap), the bottom legibility scrim
 * (or a flat dim overlay when [selected]) above the media, and its own tap target (Role.Button, ≥48dp,
 * long-press enters selection and toggles — spec 2026-07-04 anatomy).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BentoPane(
    selected: Boolean,
    reduceMotion: Boolean,
    label: String,
    bitmap: Bitmap?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val target = if (selected) 0.94f else PressFeedback.targetScale(pressed, reduceMotion)
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = 120),
        label = "bentoTileScale",
    )
    val hi = MaterialTheme.colorScheme.surfaceContainerHigh
    val lo = MaterialTheme.colorScheme.surface
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .angledSurfaceGradient(hi, lo)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onTap,
                onLongClick = onLongPress,
            )
            .semantics {
                role = Role.Button
                contentDescription = label
            },
    ) {
        VideoFrame(bitmap, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(if (selected) Brush.linearGradient(listOf(SELECTED_DIM, SELECTED_DIM)) else bottomScrimBrush()),
        )
    }
}

/**
 * 140° gradient background (CSS angle convention: clockwise from "up"), surfaceContainerHigh -> surface
 * — the frozen placeholder anatomy. Computed from the real draw [size] (drawWithCache) so the direction
 * is correct regardless of the tile's span/height.
 */
private fun Modifier.angledSurfaceGradient(hi: Color, lo: Color): Modifier = drawWithCache {
    val rad = Math.toRadians(140.0)
    val dx = sin(rad).toFloat()
    val dy = -cos(rad).toFloat()
    val cx = size.width / 2f
    val cy = size.height / 2f
    val len = (abs(size.width * dx) + abs(size.height * dy)) / 2f
    val start = Offset(cx - dx * len, cy - dy * len)
    val end = Offset(cx + dx * len, cy + dy * len)
    val brush = Brush.linearGradient(listOf(hi, lo), start = start, end = end)
    onDrawBehind { drawRect(brush) }
}

/** Frozen bottom legibility scrim: 4-stop vertical gradient (spec 2026-07-04 anatomy). */
private fun bottomScrimBrush(): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0f to SEAM_COLOR.copy(alpha = 0.16f),
        0.26f to SEAM_COLOR.copy(alpha = 0f),
        0.52f to SEAM_COLOR.copy(alpha = 0f),
        1f to SEAM_COLOR.copy(alpha = 0.58f),
    ),
)

@Composable
private fun MetaPill(orientation: LibraryOrientation?, dual: Boolean, timeLabel: String, durationLabel: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clearAndSetSemantics {}
            .background(META_PILL_BG, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dual) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.5.dp)) {
                OrientationGlyph(LibraryOrientation.PORTRAIT, Modifier.size(11.dp))
                OrientationGlyph(LibraryOrientation.LANDSCAPE, Modifier.size(11.dp))
            }
            Spacer(Modifier.width(5.dp))
        } else if (orientation != null) {
            OrientationGlyph(orientation, Modifier.size(11.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            timeLabel,
            color = Color.White.copy(alpha = 0.95f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
        )
        if (durationLabel.isNotEmpty()) {
            val durationTail = " · $durationLabel" // separator, not user copy — kept out of Text( as a literal
            Text(
                durationTail,
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
            )
        }
    }
}

@Composable
private fun LatestChip(modifier: Modifier = Modifier) {
    val colors = rememberLibraryColors()
    Box(
        modifier
            .clearAndSetSemantics {}
            .background(colors.accentFill, RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            stringResource(R.string.library_bento_latest_chip),
            color = colors.accentInk,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.1.em,
        )
    }
}

@Composable
private fun SelectionCheck(selected: Boolean, accentFill: Color, accentInk: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clearAndSetSemantics {}
            .size(22.dp)
            .background(if (selected) accentFill else Color.White.copy(alpha = 0.16f), RoundedCornerShape(50))
            .then(if (selected) Modifier else Modifier.border(1.5.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(50))),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = RovaIcons.Select.outline,
                contentDescription = null,
                tint = accentInk,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
