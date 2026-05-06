package com.aritr.rova.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aritr.rova.ui.components.RovaAnimations.pulsingOpacity

/**
 * Slice 3 — persistent status strip for the active HUD. Identical
 * layout for Recording (REC + pulsing dot) and Waiting (WAIT,
 * non-pulsing) variants; only the badge content/colors and right-side
 * label differ.
 *
 * Active-state cue is text-first: REC and WAIT are spelled out
 * verbatim, never carried by color alone (per UI_ROADMAP §"Design
 * principles" item 2).
 *
 * **A11y boundary.** This composable does NOT carry a `liveRegion`.
 * Elapsed-time announcements are owned exclusively by [SessionTimer],
 * which throttles them to minute boundaries. The strip exposes a
 * static [contentDescription] describing the state + loop position so
 * TalkBack reads it on focus, not on every elapsed-time tick — two
 * polite live regions fed by the same minute counter would otherwise
 * announce the same boundary twice.
 */
@Composable
fun RecordStatusStrip(
    isRecording: Boolean,
    currentLoop: Int,
    totalLoops: Int,
    totalElapsedSeconds: Long,
    modifier: Modifier = Modifier
) {
    val badgeBg = if (isRecording) {
        MaterialTheme.colorScheme.error
    } else {
        Color.White.copy(alpha = 0.18f)
    }
    val badgeFg = if (isRecording) MaterialTheme.colorScheme.onError else Color.White
    val badgeLabel = if (isRecording) "REC" else "WAIT"
    val loopText = RecordHudFormatters.formatLoopPosition(currentLoop, totalLoops)
    val totalText = RecordHudFormatters.formatMmSs(totalElapsedSeconds)
    val a11y = buildString {
        append(if (isRecording) "Recording" else "Waiting between clips")
        append(", ")
        append(loopText)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = a11y },
        shape = RoundedCornerShape(14.dp),
        color = Color.Black.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(badgeBg)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(badgeFg)
                                .alpha(pulsingOpacity())
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = badgeLabel,
                        color = badgeFg,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp
                        )
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = loopText,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier.semantics { contentDescription = loopText }
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Total ",
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = totalText,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum"
                    )
                )
            }
        }
    }
}
