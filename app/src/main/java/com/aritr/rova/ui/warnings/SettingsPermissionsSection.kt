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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Phase 4.2 — "Permissions & status" section rendered at the top of
 * SettingsScreen. Each chip uses the same v3 severity tokens as the
 * History strip (via [accentFor]/[severityLabelFor] reused from
 * [HistoryWarningStrip]'s file). No X button — Settings chips are
 * non-dismissable per spec §3.5; chip disappears only when the
 * underlying signal flips off.
 *
 * Empty list → entire section (header + content) is hidden.
 */
@Composable
internal fun SettingsPermissionsSection(
    warningIds: List<WarningId>,
    onOpenSheet: (WarningId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (warningIds.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            text = "PERMISSIONS & STATUS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            warningIds.forEach { id ->
                SettingsPermissionChip(id = id, onClick = { onOpenSheet(id) })
            }
        }
    }
}

@Composable
private fun SettingsPermissionChip(id: WarningId, onClick: () -> Unit) {
    val surface = warningSurfaceFor(id)
    val accent = accentFor(surface)
    val content = warningSheetContent(id)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .border(1.dp, accent.copy(alpha = 0.50f), RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(
                text = severityLabelFor(surface),
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = content.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Phase 4.2 — sheet host for Settings "Permissions & status" chip taps.
 * Simpler than [HistoryWarningSheetHost] because SETTINGS_WARNINGS does
 * NOT include CANT_MERGE — no recovery callbacks are required.
 */
@Composable
internal fun SettingsPermissionsSheetHost(
    id: WarningId,
    vm: WarningCenterViewModel,
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
            )
            onDismiss()
        },
        onSecondary = {
            // Settings allowlist has no recovery targets — secondary is dismiss-only.
            onDismiss()
        },
        onTertiary = {
            // Settings allowlist has no tertiary CTAs.
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
