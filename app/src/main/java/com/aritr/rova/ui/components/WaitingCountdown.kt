package com.aritr.rova.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.theme.NumericMonoMedium

/**
 * Slice 3 — between-clips countdown card for the Waiting variant of
 * the active HUD. Mounted only when [RecordHudState.Waiting] is the
 * resolved HUD state.
 *
 * The countdown text intentionally is NOT a `liveRegion` — TalkBack
 * users would otherwise hear a per-second tick chant. The
 * announcement is folded into the parent status strip's polite live
 * region instead, which the timer composable updates only on minute
 * boundaries.
 */
@Composable
fun WaitingCountdown(
    nextClipInSeconds: Long,
    currentLoop: Int,
    totalLoops: Int,
    modifier: Modifier = Modifier
) {
    val countdown = RecordHudFormatters.formatMmSs(nextClipInSeconds)
    val remaining = RecordHudFormatters.formatLoopsRemaining(currentLoop, totalLoops)
    val a11y = "Next clip in $countdown. $remaining"

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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Next clip in",
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = countdown,
                color = Color.White,
                style = NumericMonoMedium
            )
            Text(
                text = remaining,
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
