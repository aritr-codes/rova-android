package com.aritr.rova.ui.warnings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aritr.rova.R
import com.aritr.rova.ui.components.focusHighlight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
            text = stringResource(R.string.warning_settings_section_header),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            // WCAG 2.2 AA SC 1.3.1 (ADR-0020, SET-14): section header as a
            // heading for TalkBack navigation.
            modifier = Modifier
                .padding(start = 4.dp, top = 12.dp, bottom = 8.dp)
                .semantics { heading() },
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            warningIds.forEach { id ->
                SettingsPermissionChip(id = id, onClick = { onOpenSheet(id) })
            }
        }
    }
}

/**
 * M7 (parity plan §7) — transcribes the frozen `.setchip` (`warnings-recovery.html`
 * §06 :346–:352). A THEMED host: r-sm 10, min-height 48, fill `color-mix(sev 8%,
 * surface)`, border sev 25%; a leading 7dp resolved dot (the `set` mark ink) and a
 * trailing "Fix" in resolved accent-as-text (the `set` acc ink, `MIX_ACCENT`). NOT
 * dismissible — the chip clears only when the signal flips off. Pre-M7 the chip was a
 * fixed sev-alpha island with a bordered severity badge; the resolved inks follow the
 * palette (§06) and clear AA on Daylight where a fixed mix would not.
 */
@Composable
private fun SettingsPermissionChip(id: WarningId, onClick: () -> Unit) {
    val surface = warningSurfaceFor(id)
    val accent = accentFor(surface)
    val content = warningSheetContent(id)
    val cs = MaterialTheme.colorScheme
    val ink = ThemedHostInk.forSet(
        severity = accent,
        // The "Fix" hue is the RAW palette accent, resolved as text — never the deepened
        // `colorScheme.primary` CTA fill. `inversePrimary` is `RovaPalette.accent` verbatim
        // (PaletteColorScheme.from :65), so it is the raw-accent handle a themed composable has.
        accent = cs.inversePrimary,
        surface = cs.surface,
        onSurface = cs.onSurface,
    )
    // WCAG 2.2 AA SC 4.1.2 / 1.3.1 (ADR-0020, SET-14): one button node whose
    // name folds the severity + title together, so TalkBack reads e.g.
    // "Soft status: Microphone access needed, button" instead of two stray
    // fragments. The visible severity channel is the coloured dot; the severity
    // WORD survives in this description (the frozen setchip drops the badge).
    val chipDescription = stringResource(
        R.string.warning_chip_status_cd,
        stringResource(severityLabelFor(surface)),
        stringResource(content.title),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ink.tint)
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            // SC 2.4.7 (SET-16): visible focus ring for D-pad/keyboard.
            .focusHighlight(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = stringResource(R.string.warning_open_details_label), role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = chipDescription }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(ink.dot))
        Text(
            text = stringResource(content.title),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.warning_settings_fix_action),
            style = MaterialTheme.typography.labelLarge,
            color = ink.fix,
            fontWeight = FontWeight.SemiBold,
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

    WarningSheet(
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
