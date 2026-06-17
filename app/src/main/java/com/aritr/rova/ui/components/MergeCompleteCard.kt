package com.aritr.rova.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.text.resolve
import com.aritr.rova.ui.theme.RovaIcons

/**
 * Phase 2.4 — brief "Merge Complete" card shown for a short grace
 * window between merge success and the existing auto-navigation to
 * Library. The card is a transient overlay, not a sealed
 * [RecordHudState] case: by the time the service has finished the
 * merge, both `isMerging` and `isPeriodicActive` have flipped off,
 * so the active HUD column has already torn down. The composable
 * holds a local `showCompleteCard` flag to keep this overlay
 * visible during the grace.
 *
 * The mockup phone 6 in `mockups/new_uiux/minimal-overlay-redesign.html`
 * pairs this with a "Stay here / Go to Library" two-button choice.
 * Phase 2.4 keeps the existing auto-nav contract per the GO message
 * — adding "Stay here" would change `onMergeFinished` semantics and
 * is deferred.
 *
 * Live region is `Polite` so TalkBack announces the success once;
 * the surrounding HUD bands deliberately do not carry live regions
 * so the announcement is not duplicated.
 */
@Composable
fun MergeCompleteCard(
    clipCount: Int,
    modifier: Modifier = Modifier
) {
    val summary = RecordHudFormatters.formatMergeCompleteSummary(clipCount).resolve()
    val a11y = "Merge complete. $summary"

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .semantics {
                contentDescription = a11y
                liveRegion = LiveRegionMode.Polite
            },
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ADR-0031 P1a slice 3: the merge mark through the SemanticIcon seam.
            // The card's Surface already carries the live-region a11y label.
            SemanticIcon(
                glyph = RovaIcons.Merge,
                contentDescription = null,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = "Merge Complete",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                )
            )
            Text(
                text = summary,
                // WCAG 2.2 AA SC 1.4.3 (ADR-0020, SHAR-17): the card's
                // Black@0.78 fill lightens over bright footage; 0.87α holds
                // the subtitle above 4.5:1 in that worst case.
                color = Color.White.copy(alpha = 0.87f),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
