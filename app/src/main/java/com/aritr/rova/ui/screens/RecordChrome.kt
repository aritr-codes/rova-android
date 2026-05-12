package com.aritr.rova.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.components.RecordHudState

// Screen-local style constants (see mockups/new_uiux/01-record-home.html .status-pill / .loop-pill;
// docs/UI_DESIGN_TOKENS.md decides any of these the tokens doc promotes to MaterialTheme.*).
private val GlassFill = Color.Black.copy(alpha = 0.40f)
private val GlassStroke = Color.White.copy(alpha = 0.07f)
private val StatusPillShape = RoundedCornerShape(20.dp)
private val PillShape = RoundedCornerShape(11.dp)

/**
 * The top-of-viewfinder overlay: a status pill that reads the current mode, plus,
 * in active states, a loop pill ("N/M loops done"). Placed by the caller (RecordScreen)
 * at the top of the camera Box, inset from the status-bar inset. In R1 the active-state
 * text reuses today's elapsed/countdown values; the full restyle of those is R2.
 */
@Composable
fun RecordTopOverlay(
    hudState: RecordHudState,
    statusText: String,            // e.g. "Ready to record" / "Recording" / "On break"
    statusDetail: String?,         // e.g. "0:18 left" / "next in 0:42" / null when idle
    currentLoop: Int,              // for the loop pill in active states
    totalLoops: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hudState is RecordHudState.Recording || hudState is RecordHudState.Waiting) {
            Row(
                modifier = Modifier
                    .clip(PillShape)
                    .background(GlassFill)
                    .border(1.dp, GlassStroke, PillShape)
                    .padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "${currentLoop.coerceAtLeast(0)}/${totalLoops.coerceAtLeast(0)}",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.93f),
                )
                Text(
                    text = "loops done",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.32f),
                )
            }
        }
        Row(
            modifier = Modifier
                .clip(StatusPillShape)
                .background(GlassFill)
                .border(1.dp, GlassStroke, StatusPillShape)
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            StatusDot(hudState)
            Text(statusText, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.65f))
            if (statusDetail != null) {
                Text("· $statusDetail", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.32f))
            }
        }
    }
}

@Composable
private fun StatusDot(hudState: RecordHudState) {
    val color = when (hudState) {
        RecordHudState.Recording -> Color(0xFFEF4444)
        else -> Color.White.copy(alpha = 0.25f)
    }
    Box(Modifier.size(6.dp).clip(CircleShape).background(color))
}

/** What the center button in the Record bottom nav shows / does. */
enum class RecordFabState { Start, Stop, Disabled }

/**
 * Pure derivation of the center-FAB state from the HUD state + gating booleans.
 *
 * - Idle + a hard-block active (camera permission denied OR storage insufficient
 *   to start — see [com.aritr.rova.ui.warnings.WarningCenterViewModel] / RecordScreen's
 *   leaf-signal reads) → [Disabled]; the actionable CTA lives in the auto-presented
 *   warning sheet, not the FAB.
 * - Idle, not blocked → [Start].
 * - Recording or Waiting → [Stop] (always — `sessionLocked`/`hardBlockActive` are
 *   irrelevant once a session is running).
 * - Merging → [Disabled] — the merge runs `NonCancellable` (ADR 0006), so a Stop
 *   affordance would be a lie.
 *
 * `sessionLocked` (= isPeriodicActive || isMerging) is passed for symmetry / future
 * use; the FAB is `Stop` (not `Disabled`) during an active session.
 */
fun recordFabState(
    hudState: RecordHudState,
    sessionLocked: Boolean,
    hardBlockActive: Boolean,
): RecordFabState = when (hudState) {
    RecordHudState.Idle -> if (hardBlockActive) RecordFabState.Disabled else RecordFabState.Start
    RecordHudState.Recording, RecordHudState.Waiting -> RecordFabState.Stop
    is RecordHudState.Merging -> RecordFabState.Disabled
}
