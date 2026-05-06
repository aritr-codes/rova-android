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
 * Slice 3 — current-clip progress card for the Recording variant of
 * the active HUD. Mounted only when [RecordHudState.Recording] is
 * the resolved HUD state, so it cannot coexist with
 * [WaitingCountdown].
 *
 * Progress bar uses M3 [LinearProgressIndicator]; the bar exposes
 * proper [ProgressBarRangeInfo] semantics rather than just a visual
 * fill, so TalkBack reads the live ratio rather than the surrounding
 * region's content description twice.
 */
@Composable
fun ClipProgressBand(
    elapsedSeconds: Int,
    clipSeconds: Int,
    modifier: Modifier = Modifier
) {
    val progress = RecordHudFormatters.computeClipProgress(elapsedSeconds, clipSeconds)
    val numbers = RecordHudFormatters.formatClipProgressNumbers(elapsedSeconds, clipSeconds)
    val remaining = (clipSeconds - elapsedSeconds).coerceAtLeast(0)
    val a11y = "Current clip, $elapsedSeconds of $clipSeconds seconds"

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
                    text = "Current clip",
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
            // Progress bar — M3 LinearProgressIndicator with a high-
            // contrast track so the visual is legible on a dark
            // preview without relying on color alone for the active
            // state cue (the surrounding REC badge already carries
            // text). The range-info semantics inform TalkBack of the
            // live ratio.
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
                    color = MaterialTheme.colorScheme.error,
                    trackColor = Color.Transparent
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Started this clip",
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "$remaining s left",
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontFeatureSettings = "tnum"
                    )
                )
            }
        }
    }
}
