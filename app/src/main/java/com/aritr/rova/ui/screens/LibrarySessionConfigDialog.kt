package com.aritr.rova.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.data.SessionConfig
import com.aritr.rova.ui.components.RovaAlertDialog
import com.aritr.rova.ui.theme.LocalGlassEnvironment

/**
 * Phase 2.2 — Library "View Settings" popup.
 *
 * Read-only [AlertDialog] surfacing the [SessionConfig] frozen at
 * session start for a finalized recording. Reachable from the
 * Library row's 3-dot menu; never mutates the manifest.
 *
 * Layout matches `mockups/new_uiux/03-history-library.html` Phone 3
 * — title row + four label/value rows. The `Mode` row from the
 * mockup is intentionally omitted: `SessionConfig` has no
 * orientation field in v1.0 (see `docs/UI_NAV_GRAPH.md` §6.4 +
 * `NEW_UI_BACKEND_REPLAN.md` §3.5). Phase 6 may add it once a
 * schema bump lands.
 *
 * Token contract per `docs/UI_DESIGN_TOKENS.md` §2.1 (popup row)
 * + §2.3 (radius `18.dp`): the M3 `AlertDialog` default surface
 * picks up the dark-scheme `surfaceContainerHigh` mapping; the
 * `RoundedCornerShape(18.dp)` matches the mockup popup radius.
 * No raw hex at the call site.
 */
@Composable
fun LibrarySessionConfigDialog(
    config: SessionConfig,
    onDismiss: () -> Unit
) {
    // Informational dialog: no bottom button — dismissed via the top-right X (owner 2026-06-17).
    RovaAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.history_config_title),
        dismissIcon = true,
        content = {
            val palette = LocalGlassEnvironment.current.palette
            val divider = palette.edge.copy(alpha = palette.edge.alpha * 0.6f)
            Column {
                ConfigRow(
                    label = stringResource(R.string.history_config_clip_length),
                    value = LibrarySessionConfigFormatters.formatClipLength(
                        config.durationSeconds
                    )
                )
                HorizontalDivider(color = divider)
                ConfigRow(
                    label = stringResource(R.string.history_config_repeats),
                    value = LibrarySessionConfigFormatters.formatRepeats(config.loopCount)
                )
                HorizontalDivider(color = divider)
                ConfigRow(
                    label = stringResource(R.string.history_config_wait),
                    value = LibrarySessionConfigFormatters.formatWait(config.intervalSeconds)
                )
                HorizontalDivider(color = divider)
                ConfigRow(
                    label = stringResource(R.string.history_config_quality),
                    value = LibrarySessionConfigFormatters.formatQuality(config.resolution)
                )
            }
        },
    )
}

/** Spec-sheet row — label dimmed, value high-contrast with tabular numerals for clean alignment. */
@Composable
private fun ConfigRow(label: String, value: String) {
    val palette = LocalGlassEnvironment.current.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textDim
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = palette.textHigh
        )
    }
}
