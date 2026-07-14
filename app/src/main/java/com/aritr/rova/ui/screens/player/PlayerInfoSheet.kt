package com.aritr.rova.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.screens.HistoryRowFormatters
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.theme.RovaTrustTokens

/**
 * player-info.html §01/§08 — the read-only Info sheet. Transcribes the frozen
 * three-zone Info specimen onto the SAME pinned-sheet idiom the sharing side
 * sheet uses ([PlayerShareSideSheet]): opaque [RovaTrustTokens.pinSurface],
 * inherited over-media ink, swipe-down-dismiss + focus-trap free from
 * [ModalBottomSheet]. It has NO controls — dismissal is the only interaction
 * (§00 "read, not a probe"; §11 "read-only, no controls").
 *
 * Order is frozen (§01/§13): provenance banner → Basics → Capture (single) or
 * Angles (DualShot). Every field is a real value from [PlayerInfoModel] (Ready +
 * the already-loaded manifest); nothing is decoded, fabricated, or written.
 *
 * Token discipline (§99): the specimen's amber `.prov.warn` wash is STAGE chrome
 * (`--note`, annotated "this spec PAGE only; NOT app design") and §99 introduces
 * NO new info token — so the warn/normal distinction rides the GLYPH (✓ vs !),
 * not a colour, on inherited over-media ink alone. Tone stays calm, never
 * alarming (§05).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerInfoSheet(
    model: PlayerInfoModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = RovaTrustTokens.pinSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.player_info_title),
                style = MaterialTheme.typography.titleMedium,
                color = RovaTrustTokens.mediaInk,
                modifier = Modifier.padding(vertical = 2.dp),
            )
            Text(
                text = subtitle(model),
                style = MaterialTheme.typography.bodySmall,
                color = RovaTrustTokens.mediaInkBody,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            ProvenanceBanner(model.provenance)

            // ── Basics (§03) ──
            InfoGroup(stringResource(R.string.player_info_group_basics)) {
                InfoRow(stringResource(R.string.player_info_row_started), primaryDate(model.startedAt))
                InfoRow(
                    label = stringResource(
                        if (model.keptRaw) R.string.player_info_row_length_kept
                        else R.string.player_info_row_length,
                    ),
                    value = PlayerInfoModel.humanDuration(model.durationMs),
                )
                InfoRow(
                    label = stringResource(clipsLabelRes(model)),
                    value = model.clips.toString(),
                )
                InfoRow(
                    label = stringResource(
                        if (model.angles != null) R.string.player_info_row_size_total
                        else R.string.player_info_row_size,
                    ),
                    value = HistoryRowFormatters.formatSize(model.totalSizeBytes),
                )
            }

            val angles = model.angles
            if (angles != null) {
                // ── Angles (§06) — describe both sides, reviewed marked, no synthesis ──
                InfoGroup(stringResource(R.string.player_info_group_angles)) {
                    AngleRow(model, angles, VideoSide.PORTRAIT)
                    AngleRow(model, angles, VideoSide.LANDSCAPE)
                }
            } else {
                // ── Capture (§03) — single-mode only ──
                InfoGroup(stringResource(R.string.player_info_group_capture)) {
                    InfoRow(stringResource(R.string.player_info_row_mode), modeValue(model))
                    InfoRow(stringResource(R.string.player_info_row_quality), model.requestedQuality)
                    InfoRow(
                        label = stringResource(R.string.player_info_row_interval),
                        value = pluralStringResource(
                            R.plurals.player_info_interval,
                            model.loopCount,
                            PlayerInfoModel.humanDuration(model.intervalSeconds * 1000L),
                            model.loopCount,
                        ),
                    )
                    InfoRow(
                        label = stringResource(R.string.player_info_row_audio),
                        value = stringResource(
                            if (model.audioOn) R.string.player_info_audio_on
                            else R.string.player_info_audio_off,
                        ),
                    )
                    // §07 — an honest muted "not created" row when no merged output exists.
                    if (!model.hasMergedOutput) {
                        InfoRow(
                            label = stringResource(R.string.player_info_row_combined),
                            value = stringResource(R.string.player_info_combined_not_created),
                            muted = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProvenanceBanner(provenance: PlayerInfoModel.Provenance) {
    val glyph = if (provenance.warn) Icons.Outlined.ErrorOutline else Icons.Filled.CheckCircle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp)) // --r-sm
            .padding(vertical = 4.dp)
            // §08 — the banner reads as one node: outcome + detail.
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(7.dp),
            color = com.aritr.rova.ui.theme.PlayerTokens.glyphFill,
            modifier = Modifier.size(28.dp),
        ) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                SemanticIcon(
                    imageVector = glyph,
                    contentDescription = null,
                    role = IconRole.Default,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column {
            Text(
                text = stringResource(provenance.bannerRes),
                style = MaterialTheme.typography.bodyMedium,
                color = RovaTrustTokens.mediaInk,
            )
            provenance.detailRes?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = RovaTrustTokens.mediaInkBody,
                )
            }
        }
    }
}

@Composable
private fun InfoGroup(header: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            text = header.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = RovaTrustTokens.mediaInkDim,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String, muted: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            // §08 — each row is one "label, value" node for TalkBack.
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = RovaTrustTokens.mediaInkBody,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            // §07 — an expected-but-missing value is muted, never blank/fabricated.
            color = if (muted) RovaTrustTokens.mediaInkDim else RovaTrustTokens.mediaInk,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AngleRow(
    model: PlayerInfoModel,
    angles: PlayerInfoModel.Angles,
    side: VideoSide,
) {
    val sideName = stringResource(orientationRes(side))
    val reviewed = angles.reviewedSide == side
    // §06 "(reviewing)" marks the open side; no identity minted (transported).
    val label = if (reviewed) {
        stringResource(R.string.player_info_angle_reviewing_label, sideName)
    } else {
        sideName
    }
    val finalized = if (side == VideoSide.PORTRAIT) angles.portraitFinalized else angles.landscapeFinalized
    val bytes = if (side == VideoSide.PORTRAIT) angles.portraitBytes else angles.landscapeBytes
    if (finalized) {
        // "40.8 MB · FHD" — size + the REQUESTED quality (§04); no per-side decode.
        InfoRow(label, HistoryRowFormatters.formatSize(bytes) + " · " + model.requestedQuality)
    } else {
        // §06 — a side with no finalized artifact shows the honest state, not a size.
        InfoRow(label, stringResource(R.string.player_info_side_not_finalized), muted = true)
    }
}

@Composable
private fun subtitle(model: PlayerInfoModel): String {
    val date = primaryDate(model.startedAt)
    return when {
        model.angles != null ->
            date + " · " + stringResource(R.string.player_info_mode_dual) +
                " · " + stringResource(R.string.player_info_two_angles)
        model.keptRaw ->
            date + " · " + pluralStringResource(R.plurals.player_info_clips_kept, model.clips, model.clips)
        else ->
            date + " · " +
                pluralStringResource(R.plurals.library_hero_clip_count, model.clips, model.clips) +
                " · " + PlayerInfoModel.humanDuration(model.durationMs)
    }
}

@Composable
private fun modeValue(model: PlayerInfoModel): String =
    stringResource(R.string.player_info_mode_single) + " · " + stringResource(orientationRes(model.orientation))

@Composable
private fun primaryDate(startedAt: Long): String =
    HistoryRowFormatters.formatPrimaryDateTime(startedAt)

private fun orientationRes(o: PlayerInfoModel.Orientation): Int = when (o) {
    PlayerInfoModel.Orientation.PORTRAIT -> R.string.player_info_orientation_portrait
    PlayerInfoModel.Orientation.LANDSCAPE -> R.string.player_info_orientation_landscape
    PlayerInfoModel.Orientation.FOLLOW -> R.string.player_info_orientation_follow
}

private fun orientationRes(side: VideoSide): Int = when (side) {
    VideoSide.PORTRAIT -> R.string.player_info_orientation_portrait
    VideoSide.LANDSCAPE -> R.string.player_info_orientation_landscape
}

private fun clipsLabelRes(model: PlayerInfoModel): Int = when {
    model.angles != null -> R.string.player_info_row_clips_per_angle
    model.keptRaw -> R.string.player_info_row_clips_kept
    else -> R.string.player_info_row_clips
}
