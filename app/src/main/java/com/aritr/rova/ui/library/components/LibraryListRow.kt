package com.aritr.rova.ui.library.components

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.RovaIcons
import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.components.RovaAnimations.pressScale
import com.aritr.rova.ui.library.LibraryDensity
import com.aritr.rova.ui.library.LibraryDensityDimens
import com.aritr.rova.ui.library.LibraryDensitySpec
import com.aritr.rova.ui.library.LibraryRow
import com.aritr.rova.ui.library.LibrarySessionSide
import com.aritr.rova.ui.library.SessionCaption
import com.aritr.rova.ui.library.SmartTitle
import com.aritr.rova.ui.library.StorageFormat
import com.aritr.rova.ui.library.rememberLibraryColors
import com.aritr.rova.ui.theme.GlassRole
import com.aritr.rova.ui.theme.GlassSurface
import java.util.Locale

/**
 * spec 2026-07-02 §3.2–3.4 — THE session row (single-list presentation). Three faces of one
 * anatomy: normal row / latest-accent row ([latest] — tinted container, hairline accent border,
 * larger thumb, eyebrow + explicit Play/Resume pill) / DualShot session row ([row.sides]
 * non-empty — two explicit ≥48dp side-action buttons; row tap = Portrait, the buttons make the
 * default visible). NO media surface — static thumbnail only (ADR-0030 amendment §2, trust rule).
 * Dimensions come from [dims] (density pref, spec §3.7). One clickable (role Button), merged label;
 * in [isSelectionMode] tap toggles (caller routes [onClick]), long-press [onLongClick] enters.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryListRow(
    row: LibraryRow,
    thumbnail: Bitmap?,
    tileDescription: String,
    durationFallback: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dualShotLabel: String = "",
    dims: LibraryDensitySpec = LibraryDensityDimens.spec(LibraryDensity.COMFORTABLE),
    latest: Boolean = false,
    latestEyebrowText: String = "",
    latestPillText: String = "",
    latestPillDescription: String = "",
    portraitWord: String = "",
    landscapeWord: String = "",
    playSideDescriptionTemplate: String = "",
    sideActionLabelTemplate: String = "",
    onPlaySide: (LibrarySessionSide) -> Unit = {},
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: () -> Unit = {},
    selectedLabel: String = "",
    notSelectedLabel: String = "",
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = rememberLibraryColors()
    val shape = RoundedCornerShape(if (latest) 16.dp else LibraryDimens.cardRadius)
    GlassSurface(
        role = GlassRole.Card,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (latest) 14.dp else LibraryDimens.screenPadH,
                vertical = LibraryDimens.cardPadV,
            )
            .pressScale(interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics {
                role = Role.Button
                contentDescription = tileDescription
                if (isSelectionMode) stateDescription = if (isSelected) selectedLabel else notSelectedLabel
            },
        shape = shape,
    ) {
        Column(
            Modifier
                .then(
                    if (latest) {
                        Modifier
                            .background(colors.latestContainer, shape)
                            .border(1.dp, colors.latestEdge, shape)
                    } else {
                        Modifier
                    },
                )
                .padding(8.dp),
        ) {
            if (latest && latestEyebrowText.isNotEmpty()) {
                Text(
                    latestEyebrowText,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.latestEyebrow,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            Row(
                Modifier.heightIn(min = dims.rowMinHeightDp.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSelectionMode) {
                    if (isSelected) {
                        SemanticIcon(
                            glyph = RovaIcons.Select,
                            contentDescription = null,
                            role = IconRole.Accent,
                            modifier = Modifier.padding(end = 8.dp).size(24.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                val thumbW = (if (latest) dims.latestThumbWidthDp else dims.thumbWidthDp).dp
                val thumbH = (if (latest) dims.latestThumbHeightDp else dims.thumbHeightDp).dp
                Box(Modifier.size(width = thumbW, height = thumbH).clip(RoundedCornerShape(LibraryDimens.pillRadius))) {
                    VideoFrame(thumbnail, Modifier.fillMaxSize())
                    // Standard → orientation glyph only; DualShot → "DualShot" label (owner request).
                    Row(
                        Modifier.align(Alignment.TopStart).padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (row.topology == CaptureTopology.DualShot) OverlayPill(dualShotLabel)
                        row.orientation?.let { OrientationFramePill(it) }
                    }
                    if (row.durationMs > 0) {
                        OverlayPill(
                            SmartTitle.durationLabel(row.durationMs),
                            Modifier.align(Alignment.BottomEnd).padding(4.dp),
                        )
                    }
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        row.badge?.let { badge ->
                            val icon = LibraryIconSpec.badgeGlyph(badge, row.badgeStopReason)
                            SemanticIcon(
                                imageVector = icon.glyph,
                                contentDescription = null,
                                status = icon.status,
                                modifier = Modifier.padding(end = 5.dp).size(14.dp),
                            )
                        }
                        Text(
                            row.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    val clipLabel =
                        if (row.clipCount > 1) pluralStringResource(R.plurals.library_hero_clip_count, row.clipCount, row.clipCount) else ""
                    val durationLabel = if (row.durationMs > 0) SmartTitle.durationLabel(row.durationMs) else durationFallback
                    val meta = SessionCaption.listMeta(
                        clipCountLabel = clipLabel,
                        durationLabel = durationLabel,
                        sizeLabel = StorageFormat.size(row.sizeBytes, Locale.getDefault()),
                    )
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // DualShot session row: two explicit, individually-focusable ≥48dp side actions
            // (spec §3.4 / ADR-0030 amendment §3). Text-only this PR; glyphs are PR-C polish.
            if (row.sides.isNotEmpty()) {
                Row(
                    Modifier.padding(start = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.sides.forEach { s ->
                        val word = if (s.side == VideoSide.PORTRAIT) portraitWord else landscapeWord
                        val dur = if (s.durationMs > 0) SmartTitle.durationLabel(s.durationMs) else durationFallback
                        val cd = String.format(playSideDescriptionTemplate, word, dur)
                        // Label built via the library_side_action_label resource template — an
                        // inline `Text("$word · $dur")` literal would trip checkNoHardcodedUiStrings
                        // (codex plan-review 2026-07-03).
                        val label = String.format(sideActionLabelTemplate, word, dur)
                        TextButton(
                            onClick = { onPlaySide(s) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = cd },
                        ) {
                            Text(label)
                        }
                    }
                }
            }
            // Latest anchor: explicit labeled pill; SAME action as the row tap (spec §3.3 —
            // the pill is the visible affordance, not a divergent action).
            if (latest && latestPillText.isNotEmpty()) {
                TextButton(
                    onClick = onClick,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = latestPillDescription },
                ) {
                    Text(latestPillText, color = colors.latestEyebrow)
                }
            }
        }
    }
}
