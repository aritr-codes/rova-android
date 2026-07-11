package com.aritr.rova.ui.warnings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.aritr.rova.R
import com.aritr.rova.ui.components.focusHighlight
import com.aritr.rova.ui.theme.RovaWarnings

/**
 * Phase 4.2 — top warning strip rendered on the History screen above
 * the existing RecoveryCardList. Each item uses the shipped v3 chrome's
 * severity tokens (via [warningSurfaceFor]). Tapping the body opens
 * the standard [WarningSheetV3] for that id; tapping X calls [onDismiss]
 * which the VM resolves via [WarningCenterViewModel.dismissOnHistoryStrip].
 *
 * Empty list → composable returns immediately (no spacer, no header).
 */
@Composable
internal fun HistoryWarningStrip(
    warningIds: List<WarningId>,
    onDismiss: (WarningId) -> Unit,
    onOpenSheet: (WarningId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (warningIds.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        warningIds.forEach { id ->
            HistoryWarningCard(
                id = id,
                onDismiss = { onDismiss(id) },
                onClick = { onOpenSheet(id) },
            )
        }
    }
}

/**
 * M7 (parity plan §7) — transcribes the frozen `.strip` (`warnings-recovery.html`
 * §06 :340–:343). A THEMED host: r-md 14 card, fill `color-mix(sev 8%, surface)`,
 * border sev 25%; the severity chip (`.sevchip.on-tint`) carries the resolved
 * `stripchip` dot + label ink; the body routes through `quietTextColor` (solid on a
 * light scheme). Pre-M7 the card was a fixed sev-alpha island with a bordered label
 * badge and no body — palette-blind on Daylight. Inks come from [ThemedHostInk] so
 * the surface paints exactly what `TrustContrastSweepTest` proves.
 */
@Composable
private fun HistoryWarningCard(
    id: WarningId,
    onDismiss: () -> Unit,
    onClick: () -> Unit,
) {
    val surface = warningSurfaceFor(id)
    val accent = accentFor(surface)
    val content = warningSheetContent(id)
    val cs = MaterialTheme.colorScheme
    val ink = ThemedHostInk.forStrip(
        severity = accent,
        surface = cs.surface,
        onSurface = cs.onSurface,
        onSurfaceVariant = cs.onSurfaceVariant,
        isDark = cs.surface.luminance() < 0.5f,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ink.tint)
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .focusHighlight(RoundedCornerShape(14.dp))
            .clickable(onClickLabel = stringResource(R.string.warning_view_action_label), role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // `.sevchip.on-tint` (:203–:209) — filled chip (sev@20%), r-sm 10, resolved
        // dot (5dp) + label. Replaces the pre-M7 bordered label badge.
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = ThemedHostInk.SEV_CHIP_FILL_ALPHA))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(ink.chipDot))
            Text(
                text = stringResource(severityLabelFor(surface)),
                style = MaterialTheme.typography.labelSmall,
                color = ink.chipLabel,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = stringResource(content.title),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(content.body),
                style = MaterialTheme.typography.bodySmall,
                color = ink.body,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.warning_dismiss_cd),
                tint = cs.onSurfaceVariant,
            )
        }
    }
}

/**
 * Phase 4.2 — severity → accent color. Mirrors the per-surface mapping
 * in [WarningSheetV3] and [WarningSnoozeChip] so the strip card chrome
 * is visually identical to the rest of the v3 surface.
 */
internal fun accentFor(surface: WarningSurface): Color = when (surface) {
    WarningSurface.HardBlockSheet -> RovaWarnings.hard
    WarningSurface.SoftSheet -> RovaWarnings.soft
    WarningSurface.AdvisorySheet -> RovaWarnings.advisory
    WarningSurface.TopBanner -> RovaWarnings.escalating
}

/** Phase 4.2 — short severity label (string resource) for compact strip / chip cards. */
@StringRes
internal fun severityLabelFor(surface: WarningSurface): Int = when (surface) {
    WarningSurface.HardBlockSheet -> R.string.warning_severity_short_hard
    WarningSurface.SoftSheet -> R.string.warning_severity_short_soft
    WarningSurface.AdvisorySheet -> R.string.warning_severity_short_advisory
    WarningSurface.TopBanner -> R.string.warning_severity_short_escalating
}

/**
 * Phase 4.2 — sheet host for History strip taps. Reuses [WarningSheetV3]
 * with callbacks wired so CANT_MERGE's KEEP_SEGMENTS_ONLY (secondary) and
 * DISCARD_RECOVERY_SESSION (tertiary) route to the host-provided
 * recovery handlers. All other targets route through [launchActionTarget].
 */
@Composable
internal fun HistoryWarningSheetHost(
    id: WarningId,
    vm: WarningCenterViewModel,
    pendingCantMergeSessionId: String?,
    onKeepRawFromSheet: (sessionId: String) -> Unit,
    onDiscardFromSheet: (sessionId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val surface = warningSurfaceFor(id)
    val expandedSet by vm.expandedWhy.collectAsStateWithLifecycle()
    val expanded = id in expandedSet

    WarningSheetV3(
        id = id,
        surface = surface,
        expanded = expanded,
        onPrimary = {
            launchActionTarget(
                context = context,
                target = warningSheetContent(id).primary.target,
                pendingCantMergeSessionId = pendingCantMergeSessionId,
                onKeepRawFromSheet = onKeepRawFromSheet,
                onDiscardFromSheet = onDiscardFromSheet,
            )
            onDismiss()
        },
        onSecondary = {
            val secondaryTarget = warningSheetContent(id).secondary?.target
            if (secondaryTarget == ActionTarget.KEEP_SEGMENTS_ONLY ||
                secondaryTarget == ActionTarget.DISCARD_RECOVERY_SESSION) {
                launchActionTarget(
                    context = context,
                    target = secondaryTarget,
                    pendingCantMergeSessionId = pendingCantMergeSessionId,
                    onKeepRawFromSheet = onKeepRawFromSheet,
                    onDiscardFromSheet = onDiscardFromSheet,
                )
            }
            onDismiss()
        },
        onTertiary = {
            warningSheetContent(id).tertiary?.let { ter ->
                launchActionTarget(
                    context = context,
                    target = ter.target,
                    pendingCantMergeSessionId = pendingCantMergeSessionId,
                    onKeepRawFromSheet = onKeepRawFromSheet,
                    onDiscardFromSheet = onDiscardFromSheet,
                )
            }
            onDismiss()
        },
        onOverflow = { target ->
            if (target == ActionTarget.SNOOZE_FOREVER) {
                vm.snoozeForever(id)
            } else {
                launchActionTarget(context = context, target = target)
            }
            onDismiss()
        },
        onToggleWhy = { vm.toggleExpandWhy(id) },
        onDismissRequest = onDismiss,
    )
}
