package com.aritr.rova.ui.warnings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.theme.RovaWarningsV3

/**
 * v3 — Mid-recording top banner. Renders a glass capsule with leading icon,
 * title + sub-text, and a trailing element — either a "STOP" CTA pill OR a
 * countdown ring when [TopBannerContent.autoAction] is non-null.
 *
 * Phase 4: the ring is static (no ticker). Phase 4.4 will pipe a real
 * `secondsRemaining` from a thermal-hysteresis source. Tapping the banner
 * does NOT stop the countdown — only the [onAction] CTA stops the session.
 *
 * Spec: docs/superpowers/specs/2026-05-23-phase-4-warning-reskin-v3-design.md §3.5
 */
@Composable
internal fun WarningTopBannerV3(
    content: TopBannerContent,
    severityColor: Color,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    /** Phase 4 Slice 2 — called when user taps an overflow menu item. Null = overflow ⋯ icon hidden. */
    onOverflow: ((ActionTarget) -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RovaWarningsV3.bannerCornerRadius))
            .background(Color(0xFF0B0D14).copy(alpha = 0.88f))
            .border(
                width = 1.dp,
                color = severityColor.copy(alpha = 0.30f),
                shape = RoundedCornerShape(RovaWarningsV3.bannerCornerRadius),
            )
            .padding(
                horizontal = RovaWarningsV3.bannerSidePadding,
                vertical = RovaWarningsV3.bannerVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            modifier = Modifier
                .size(RovaWarningsV3.bannerIconSize)
                .clip(RoundedCornerShape(RovaWarningsV3.bannerIconCornerRadius))
                .background(severityColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = content.icon,
                contentDescription = null,
                tint = severityColor.copy(alpha = 0.95f),
                modifier = Modifier.size(18.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = content.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.88f),
                maxLines = 1,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = content.autoAction?.description ?: content.sub,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.48f),
                maxLines = 2,
            )
        }

        val autoAction = content.autoAction
        if (autoAction != null) {
            CountdownRing(
                secondsRemaining = autoAction.secondsRemaining,
                totalSeconds = 30,                 // matches the static placeholder
                severityColor = severityColor,
            )
        } else {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(severityColor.copy(alpha = 0.20f))
                    .clickable { onAction() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = content.cta.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = severityColor.copy(alpha = 0.95f),
                )
            }
        }

        // Phase 4 Slice 2 — overflow ⋯ icon rendered when content.overflow
        // is non-empty AND a handler is wired. Tapping opens a DropdownMenu
        // listing each WarningAction; selecting an item dispatches via
        // onOverflow with the action's ActionTarget.
        if (content.overflow.isNotEmpty() && onOverflow != null) {
            Box {
                var expanded by remember { mutableStateOf(false) }
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { expanded = true },
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    content.overflow.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.label) },
                            onClick = {
                                expanded = false
                                onOverflow(action.target)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Severity-tinted countdown ring. Static in Phase 4 — Phase 4.4 will swap
 * [secondsRemaining] to read from a ThermalAutoStopSignal flow.
 */
@Composable
private fun CountdownRing(
    secondsRemaining: Int,
    totalSeconds: Int,
    severityColor: Color,
) {
    Box(
        modifier = Modifier.size(RovaWarningsV3.bannerCountdownRingSize),
        contentAlignment = Alignment.Center,
    ) {
        val progressFraction = (secondsRemaining.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
        Canvas(modifier = Modifier.size(RovaWarningsV3.bannerCountdownRingSize)) {
            val strokePx = RovaWarningsV3.bannerCountdownRingStroke.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            // Background track
            drawArc(
                color = Color.White.copy(alpha = RovaWarningsV3.bannerCountdownTrackAlpha),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx),
            )
            // Foreground arc
            drawArc(
                color = severityColor.copy(alpha = 0.85f),
                startAngle = -90f,
                sweepAngle = 360f * progressFraction,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }
        Text(
            text = secondsRemaining.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}
