package com.aritr.rova.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aritr.rova.data.SessionConfig

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
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        title = {
            Text(
                text = "Recording Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConfigRow(
                    label = "Clip length",
                    value = LibrarySessionConfigFormatters.formatClipLength(
                        config.durationSeconds
                    )
                )
                ConfigRow(
                    label = "Repeats",
                    value = LibrarySessionConfigFormatters.formatRepeats(config.loopCount)
                )
                ConfigRow(
                    label = "Wait",
                    value = LibrarySessionConfigFormatters.formatWait(config.intervalMinutes)
                )
                ConfigRow(
                    label = "Quality",
                    value = LibrarySessionConfigFormatters.formatQuality(config.resolution)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ConfigRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
