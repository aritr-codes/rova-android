package com.aritr.rova.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Phase 2.4 — in-HUD merge progress card. Mounted only when
 * [RecordHudState.Merging] is the resolved HUD state, replacing the
 * legacy modal `AlertDialog` overlay that used to fight with the
 * recording-locked chrome for focus.
 *
 * Visually mirrors [ClipProgressBand]: same Surface treatment, same
 * `LinearProgressIndicator` with [ProgressBarRangeInfo] semantics so
 * TalkBack reads the live ratio instead of the static surrounding
 * description. The card replaces the StatusStrip + SessionTimer +
 * Stop button while merging — there is no merge-cancel API today,
 * and `performMerge` runs on `NonCancellable` for terminal-write
 * atomicity (ADR 0006), so a Stop affordance would be a visual lie.
 */
@Composable
fun MergingProgressBand(
    progress: Float,
    currentSegment: Int,
    totalSegments: Int,
    modifier: Modifier = Modifier
) {
    val numbers = RecordHudFormatters.formatMergeProgressNumbers(currentSegment, totalSegments)
    val sub = RecordHudFormatters.formatMergeRemaining(progress)
    val a11y = RecordHudFormatters.formatMergeAnnouncement(currentSegment, totalSegments)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = a11y },
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Merging clips",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium
                    )
                )
                Text(
                    text = numbers,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum"
                    )
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.16f))
                    .semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            current = progress,
                            range = 0f..1f
                        )
                    }
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )
            }
            if (!sub.isNullOrBlank()) {
                Text(
                    text = sub,
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
