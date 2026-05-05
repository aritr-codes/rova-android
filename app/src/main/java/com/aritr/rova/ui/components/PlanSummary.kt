package com.aritr.rova.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.theme.RovaTheme

/**
 * Pure presentation helper that maps the four session-plan inputs into
 * one human sentence. Sentinels never reach the returned string:
 *  - [loopCount] == -1  → "until stopped"
 *  - [intervalMinutes] == 0 → "no wait"
 *
 * Result example: `"30s clips · 10 repeats · 1m wait · FHD"`.
 *
 * Pure (no Compose, no Android). Round-tripped from VM values; the
 * sentinel restoration happens at the VM-write boundary, not here.
 */
fun formatSessionPlan(
    clipSeconds: Int,
    loopCount: Int,
    intervalMinutes: Int,
    quality: String
): String {
    val clipPart = formatClipLength(clipSeconds)
    val repeatsPart = if (loopCount < 0) "until stopped" else "$loopCount repeats"
    val waitPart = formatWait(intervalMinutes)
    return "$clipPart · $repeatsPart · $waitPart · $quality"
}

private fun formatClipLength(seconds: Int): String = when {
    seconds <= 0 -> "0s clips"
    seconds < 60 -> "${seconds}s clips"
    seconds % 60 == 0 -> "${seconds / 60}m clips"
    else -> "${seconds}s clips"
}

private fun formatWait(intervalMinutes: Int): String = when {
    intervalMinutes <= 0 -> "no wait"
    intervalMinutes == 60 -> "1h wait"
    intervalMinutes % 60 == 0 -> "${intervalMinutes / 60}h wait"
    else -> "${intervalMinutes}m wait"
}

@Composable
fun PlanSummary(
    clipSeconds: Int,
    loopCount: Int,
    intervalMinutes: Int,
    quality: String,
    modifier: Modifier = Modifier
) {
    val summary = formatSessionPlan(clipSeconds, loopCount, intervalMinutes, quality)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Session plan: $summary" },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = "Session plan",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Preview(name = "PlanSummary · light", showBackground = true)
@Composable
private fun PlanSummaryPreviewLight() {
    RovaTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp)) {
                PlanSummary(30, 10, 1, "FHD")
                Spacer(Modifier.height(8.dp))
                PlanSummary(30, -1, 0, "FHD")
            }
        }
    }
}

@Preview(name = "PlanSummary · dark", showBackground = true)
@Composable
private fun PlanSummaryPreviewDark() {
    RovaTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp)) {
                PlanSummary(60, 5, 30, "HD")
                Spacer(Modifier.height(8.dp))
                PlanSummary(30, 10, 0, "4K")
            }
        }
    }
}
