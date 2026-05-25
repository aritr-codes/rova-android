package com.aritr.rova.ui.warnings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.theme.RovaWarnings
import com.aritr.rova.ui.theme.RovaWarningsV3

/**
 * v3 — Modal bottom sheet for all idle-reachable warnings. Routes by
 * [surface] to compute the severity accent.
 *
 * Layout:
 *   handle → overflow ⋯ (if hasOverflow) → icon-with-glow → severity chip →
 *   title → body → optional "Why this matters" expander → primary CTA →
 *   secondary CTA (if present) → tertiary CTA (if present, Phase 4.3).
 *
 * Hard-block sheets are NOT swipe-dismissible (`confirmValueChange`).
 *
 * Spec: docs/superpowers/specs/2026-05-23-phase-4-warning-reskin-v3-design.md §3.4
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WarningSheetV3(
    id: WarningId,
    surface: WarningSurface,
    expanded: Boolean,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    /** Phase 4.3 — C2.4 CANT_MERGE tertiary "Discard session" link. No-op (default) for all other warnings. */
    onTertiary: () -> Unit = {},
    onOverflow: (ActionTarget) -> Unit,
    onToggleWhy: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val content = warningSheetContent(id)
    val accent: Color = when (surface) {
        WarningSurface.HardBlockSheet -> RovaWarnings.hard
        WarningSurface.SoftSheet -> RovaWarnings.soft
        WarningSurface.AdvisorySheet -> RovaWarnings.advisory
        WarningSurface.TopBanner -> RovaWarnings.advisory   // unreachable here
    }
    val severityLabel: String = when (surface) {
        WarningSurface.HardBlockSheet -> "Hard · Required"
        WarningSurface.SoftSheet -> "Soft · Degraded mode"
        WarningSurface.AdvisorySheet -> "Advisory · Optional"
        WarningSurface.TopBanner -> ""
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            surface != WarningSurface.HardBlockSheet || value != SheetValue.Hidden
        },
    )

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(
            topStart = RovaWarningsV3.sheetCornerRadius,
            topEnd = RovaWarningsV3.sheetCornerRadius,
            bottomStart = 0.dp,
            bottomEnd = 0.dp,
        ),
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {

            if (hasOverflow(id)) {
                OverflowMenu(
                    overflow = content.overflow,
                    onTarget = onOverflow,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(
                            top = RovaWarningsV3.overflowTopInset,
                            end = RovaWarningsV3.overflowRightInset,
                        ),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = RovaWarningsV3.sheetSidePadding,
                        end = RovaWarningsV3.sheetSidePadding,
                        bottom = RovaWarningsV3.sheetBottomPadding,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))

                IconWithGlow(icon = content.icon, accent = accent)

                Spacer(Modifier.height(10.dp))

                SeverityChip(label = severityLabel, accent = accent)

                Spacer(Modifier.height(8.dp))

                Text(
                    text = content.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.94f),
                    textAlign = TextAlign.Center,
                )

                if (content.body.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = content.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.45f),
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(18.dp))

                if (shouldShowWhy(id)) {
                    WhyExpander(
                        expanded = expanded,
                        whyBody = content.whyThisMatters!!,
                        onToggle = onToggleWhy,
                    )
                    Spacer(Modifier.height(10.dp))
                }

                PrimaryCta(
                    label = content.primary.label,
                    accent = accent,
                    onClick = onPrimary,
                )

                content.secondary?.let { sec ->
                    Spacer(Modifier.height(8.dp))
                    SecondaryCta(label = sec.label, onClick = onSecondary)
                }

                // Phase 4.3 — tertiary CTA (e.g. C2.4 CANT_MERGE "Discard session").
                // Only Link style is used for tertiary in this slice; the when-block is
                // exhaustive so future styles (Secondary/Primary) compile cleanly.
                content.tertiary?.let { ter ->
                    when (ter.style) {
                        WarningActionStyle.Link -> {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = ter.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                                color = RovaWarnings.hard,
                                textDecoration = TextDecoration.Underline,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .clickable { onTertiary() },
                            )
                        }
                        WarningActionStyle.Secondary -> {
                            Spacer(Modifier.height(8.dp))
                            SecondaryCta(label = ter.label, onClick = onTertiary)
                        }
                        WarningActionStyle.Primary -> {
                            Spacer(Modifier.height(8.dp))
                            PrimaryCta(label = ter.label, accent = accent, onClick = onTertiary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconWithGlow(
    icon: ImageVector,
    accent: Color,
) {
    // The radial glow brush needs an explicit radius in pixels — the default
    // (Float.POSITIVE_INFINITY) collapses the gradient to a flat fill, killing
    // the bloom. Match the radius to the icon's diameter so the gradient fades
    // exactly across the bloom box.
    val density = LocalDensity.current
    val glowRadiusPx = with(density) {
        (RovaWarningsV3.sheetIconSize - (RovaWarningsV3.sheetIconGlowInset * 2)).toPx() * 0.5f
    }

    Box(
        modifier = Modifier.size(RovaWarningsV3.sheetIconSize),
        contentAlignment = Alignment.Center,
    ) {
        // Glow bloom (offset + blurred)
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(
                    x = RovaWarningsV3.sheetIconGlowInset,
                    y = RovaWarningsV3.sheetIconGlowInset,
                )
                .size(
                    width = RovaWarningsV3.sheetIconSize - (RovaWarningsV3.sheetIconGlowInset * 2),
                    height = RovaWarningsV3.sheetIconSize - (RovaWarningsV3.sheetIconGlowInset * 2),
                )
                .blur(RovaWarningsV3.sheetIconGlowBlur)
                .background(brush = RovaWarningsV3.iconGlow(accent, glowRadiusPx), shape = CircleShape),
        )
        // Icon container
        Box(
            modifier = Modifier
                .size(RovaWarningsV3.sheetIconSize)
                .clip(RoundedCornerShape(RovaWarningsV3.sheetIconCornerRadius))
                .background(accent.copy(alpha = 0.14f))
                .border(
                    width = 1.dp,
                    color = accent.copy(alpha = RovaWarningsV3.sheetIconInnerStrokeAlpha),
                    shape = RoundedCornerShape(RovaWarningsV3.sheetIconCornerRadius),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = accent)
        }
    }
}

@Composable
private fun SeverityChip(label: String, accent: Color) {
    if (label.isBlank()) return
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(RovaWarningsV3.snoozeChipRadius))
            .background(accent.copy(alpha = RovaWarningsV3.sevChipFillAlpha))
            .padding(
                horizontal = RovaWarningsV3.sevChipPaddingH,
                vertical = RovaWarningsV3.sevChipPaddingV,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(RovaWarningsV3.sevChipDotSize)
                .clip(CircleShape)
                .background(accent),
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent.copy(alpha = RovaWarningsV3.sevChipForegroundAlpha),
        )
    }
}

@Composable
private fun WhyExpander(
    expanded: Boolean,
    whyBody: String,
    onToggle: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(RovaWarningsV3.whyRowHeight)
                .clip(RoundedCornerShape(RovaWarningsV3.whyRowCornerRadius))
                .border(
                    width = 1.dp,
                    color = RovaWarnings.advisory.copy(alpha = RovaWarningsV3.whyRowBorderAlpha),
                    shape = RoundedCornerShape(RovaWarningsV3.whyRowCornerRadius),
                )
                .clickable { onToggle() }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = RovaWarnings.advisory.copy(alpha = RovaWarningsV3.whyRowForegroundAlpha),
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "Why this matters",
                style = MaterialTheme.typography.labelMedium,
                color = RovaWarnings.advisory.copy(alpha = RovaWarningsV3.whyRowForegroundAlpha),
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = RovaWarnings.advisory.copy(alpha = RovaWarningsV3.whyRowForegroundAlpha),
                modifier = Modifier.size(14.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = whyBody,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun PrimaryCta(label: String, accent: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RovaWarningsV3.sheetCtaHeight)
            .clip(RoundedCornerShape(RovaWarningsV3.sheetCtaCornerRadius))
            .background(accent)
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

@Composable
private fun SecondaryCta(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RovaWarningsV3.sheetCtaHeight)
            .clip(RoundedCornerShape(RovaWarningsV3.sheetCtaCornerRadius))
            .background(Color.White.copy(alpha = RovaWarningsV3.secondaryCtaFillAlpha))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = RovaWarningsV3.secondaryCtaStrokeAlpha),
                shape = RoundedCornerShape(RovaWarningsV3.sheetCtaCornerRadius),
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = RovaWarningsV3.secondaryCtaTextAlpha),
        )
    }
}

@Composable
private fun OverflowMenu(
    overflow: List<WarningAction>,
    onTarget: (ActionTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.size(RovaWarningsV3.overflowButtonSize),
        ) {
            Icon(
                imageVector = Icons.Default.MoreHoriz,
                contentDescription = "More actions",
                tint = Color.White.copy(alpha = 0.30f),
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            overflow.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    onClick = {
                        menuOpen = false
                        onTarget(action.target)
                    },
                )
            }
        }
    }
}
