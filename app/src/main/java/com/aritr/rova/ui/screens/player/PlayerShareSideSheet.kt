package com.aritr.rova.ui.screens.player

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.theme.RovaTrustTokens

/**
 * player-sharing.html §04 — the one Rova-owned surface in the share flow. A
 * lightweight pinned sheet (opaque [RovaTrustTokens.pinSurface], inherited
 * over-media ink, swipe-down-dismiss + focus-trap free from [ModalBottomSheet]),
 * shown ONLY when a DualShot session has both sides shareable
 * ([PlayerSharePlan.DualChoice]). Three options — the reviewed side pre-listed
 * as the default, the other side, and both — each a real ≥48dp labelled control
 * (§09). Picking an option resolves + launches the system chooser; it is not a
 * chooser itself (§04).
 *
 * No identity is reconstructed (§04): the reviewed side is the transported
 * [PlayerShareArtifact]; the other / both read the plan's per-side fields.
 * Reduce-motion (§11) is honored at the behavior level — every option stays
 * present and reachable; only the entrance animation differs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerShareSideSheet(
    plan: PlayerSharePlan.DualChoice,
    onShare: (List<PlayerShareArtifact>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val reviewedSide = plan.reviewed.side ?: VideoSide.PORTRAIT
    val otherSide = plan.other.side ?: PlayerSharePlan.other(reviewedSide)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = RovaTrustTokens.pinSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.player_share_sheet_title),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = RovaTrustTokens.mediaInk,
                modifier = Modifier.padding(vertical = 2.dp),
            )
            Text(
                text = stringResource(R.string.player_share_sheet_subtitle),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = RovaTrustTokens.mediaInkBody,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // §04 — "This angle · <reviewed>" (default), the other side, both.
            ShareOption(
                label = stringResource(R.string.player_share_option_this_angle, sideName(reviewedSide)),
                sub = stringResource(R.string.player_share_option_this_angle_sub),
                onClick = { onShare(listOf(plan.reviewed)) },
            )
            ShareOption(
                label = stringResource(R.string.player_share_option_other_angle, sideName(otherSide)),
                sub = stringResource(R.string.player_share_option_other_angle_sub),
                onClick = { onShare(listOf(plan.other)) },
            )
            ShareOption(
                label = stringResource(R.string.player_share_option_both),
                sub = stringResource(R.string.player_share_option_both_sub),
                onClick = { onShare(plan.both) },
            )
        }
    }
}

@Composable
private fun ShareOption(
    label: String,
    sub: String,
    onClick: () -> Unit,
) {
    // Surface(onClick) supplies Role.Button (checkA11yClickableHasRole);
    // mergeDescendants folds the label + sub Texts into one spoken option (§09).
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Column(modifier = Modifier.padding(vertical = 10.dp)) {
            Text(
                text = label,
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = RovaTrustTokens.mediaInk,
            )
            Text(
                text = sub,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = RovaTrustTokens.mediaInkDim,
            )
        }
    }
}

@Composable
private fun sideName(side: VideoSide): String =
    stringResource(sideNameRes(side))

@StringRes
private fun sideNameRes(side: VideoSide): Int = when (side) {
    VideoSide.PORTRAIT -> R.string.library_orientation_portrait
    VideoSide.LANDSCAPE -> R.string.library_orientation_landscape
}
