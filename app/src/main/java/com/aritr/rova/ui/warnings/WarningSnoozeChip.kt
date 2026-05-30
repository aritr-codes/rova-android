package com.aritr.rova.ui.warnings

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.theme.RovaWarnings
import com.aritr.rova.ui.components.rememberReduceMotion
import com.aritr.rova.ui.theme.RovaWarningsV3

/**
 * v3 — Post-dismiss chip. Replaces the anonymous `WarningChip` previously
 * inlined in `WarningCenter.kt`. Glass black α0.55 fill + severity-tinted
 * border α0.25; hard-block ids get a soft alpha pulse on the leading dot.
 *
 * Tap → caller restores via `WarningCenterViewModel.restore(id)`.
 *
 * Spec: docs/superpowers/specs/2026-05-23-phase-4-warning-reskin-v3-design.md §3.6
 */
@Composable
internal fun WarningSnoozeChip(
    id: WarningId,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = warningSheetContent(id)
    val severityColor: Color = when (warningSurfaceFor(id)) {
        WarningSurface.HardBlockSheet -> RovaWarnings.hard
        WarningSurface.SoftSheet -> RovaWarnings.soft
        WarningSurface.AdvisorySheet -> RovaWarnings.advisory
        WarningSurface.TopBanner -> RovaWarnings.escalating
    }
    val isHardBlock = warningSurfaceFor(id) == WarningSurface.HardBlockSheet

    // WCAG 2.2 AA SC 2.3.3 / 2.2.2 (ADR-0020, WARN-07): no pulse under the OS
    // reduced-motion toggle — the dot holds fully visible.
    val reduceMotion = rememberReduceMotion()
    val dotAlpha: Float = if (isHardBlock && !reduceMotion) {
        val transition = rememberInfiniteTransition(label = "snooze-chip-pulse")
        val alpha by transition.animateFloat(
            initialValue = RovaWarningsV3.snoozeChipDotPulseAlpha,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1500),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "snooze-chip-pulse-alpha",
        )
        alpha
    } else 1f

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(RovaWarningsV3.snoozeChipRadius))
            .background(Color.Black.copy(alpha = RovaWarningsV3.snoozeChipFillAlpha))
            .border(
                width = 1.dp,
                color = severityColor.copy(alpha = RovaWarningsV3.snoozeChipBorderAlpha),
                shape = RoundedCornerShape(RovaWarningsV3.snoozeChipRadius),
            )
            .clickable(onClickLabel = "Show details", role = Role.Button) { onExpand() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(severityColor.copy(alpha = dotAlpha)),
        )
        Icon(
            imageVector = content.icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.78f),
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = content.title,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.78f),
            maxLines = 1,
        )
    }
}
