package com.aritr.rova.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.theme.NumericMonoLarge

/**
 * Slice 3 — large session-elapsed timer for the active Record HUD.
 *
 * Renders the elapsed seconds as `MM:SS` (or `H:MM:SS` past one hour)
 * in the [NumericMonoLarge] tabular monospace style added in this
 * slice. The TalkBack live region announces only the
 * [announcementSeconds] value — callers throttle that to once per
 * minute boundary so per-second redraws never produce per-second
 * accessibility chatter (per UI_ROADMAP §"Slice 3 special
 * requirements").
 */
@Composable
fun SessionTimer(
    elapsedSeconds: Long,
    announcementSeconds: Long,
    label: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    contentColor: Color = LocalContentColor.current
) {
    val display = RecordHudFormatters.formatMmSs(elapsedSeconds)
    val polite = RecordHudFormatters.formatElapsedAnnouncement(announcementSeconds)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor.copy(alpha = 0.78f),
            modifier = Modifier.semantics { contentDescription = label }
        )
        Text(
            text = display,
            style = NumericMonoLarge,
            color = contentColor,
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = polite
            }
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.78f)
            )
        }
    }
}
