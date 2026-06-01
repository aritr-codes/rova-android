package com.aritr.rova.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.ui.components.RovaAnimations.pulsingOpacity

@Composable
fun BackgroundRecordingBanner(
    isRecordingInBackground: Boolean,
    nextRecordingInSeconds: Long,
    onStopClick: () -> Unit
) {
    if (isRecordingInBackground) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Pulsing Red Dot
                val alpha = pulsingOpacity()
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .alpha(alpha)
                        .background(Color.Red, CircleShape)
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Column {
                    Text(
                        text = stringResource(R.string.record_bg_recording),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = stringResource(
                            R.string.record_bg_next_recording,
                            formatSeconds(nextRecordingInSeconds)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            TextButton(onClick = onStopClick) {
                Text(stringResource(R.string.record_bg_stop), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun formatSeconds(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format("%d:%02d", mins, secs)
}
