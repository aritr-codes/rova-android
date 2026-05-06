package com.aritr.rova.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Slice 2 — read-only echo of the app-level recovery report on the
 * Record idle screen. Reads the existing `RovaApp.recoveryReport`
 * StateFlow only; carries no Discard control (destructive action lives
 * exclusively on the History card per Slice 4 + Slice 5 ownership).
 *
 * Hidden by the caller when the report is empty; this composable
 * assumes [interruptedCount] > 0.
 *
 * The [interruptedCount] argument is a count of *sessions* that the
 * History surface would render (visible card + hidden footer count).
 * The caller is responsible for applying the same eligibility filter
 * History uses (see `RecoveryViewSource.eligibleSessionCount`) so this
 * banner cannot lie about whether History has anything to show. The
 * copy is intentionally session-scoped ("interrupted session"), not
 * segment-scoped — actual segment counts live on the History card.
 *
 * Visual: softened surface-container background with a 4 dp `error`
 * left stripe — important and informational, not alarming. The CTA
 * routes to the History tab where the full recovery card lives.
 */
@Composable
fun RecoveryEchoBanner(
    interruptedCount: Int,
    onReviewInHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "A recording was interrupted. " +
                    bodyCopy(interruptedCount)
            },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.error)
            )
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "A recording was interrupted",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = bodyCopy(interruptedCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onReviewInHistory) {
                        Text("Review in History")
                    }
                }
            }
        }
    }
}

private fun bodyCopy(interruptedCount: Int): String =
    if (interruptedCount == 1) {
        "1 interrupted session. Open History to keep or discard."
    } else {
        "$interruptedCount interrupted sessions. Open History to keep or discard."
    }

