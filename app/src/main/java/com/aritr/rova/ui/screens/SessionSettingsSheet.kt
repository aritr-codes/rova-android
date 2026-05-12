@file:OptIn(ExperimentalMaterial3Api::class)

package com.aritr.rova.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

// ── Settings-card / settings-sheet display-value formatters (sentinel-blind).
// Accessibility descriptions live in com.aritr.rova.ui.components.UiCopy; these
// are the short cell/row VALUES. Moved here from the old RecordIdleDock.kt.

internal fun recordClipValue(seconds: Int): String = when {
    seconds <= 0 -> "0 s"
    seconds < 60 -> "$seconds s"
    seconds == 60 -> "1 m"
    seconds % 60 == 0 -> "${seconds / 60} m"
    else -> "$seconds s"
}

internal fun recordRepeatsValue(loopCount: Int): String =
    if (loopCount < 0) "Until you stop" else loopCount.toString()

internal fun recordWaitValue(intervalMinutes: Int): String = when {
    intervalMinutes <= 0 -> "None"
    intervalMinutes == 60 -> "1 h"
    intervalMinutes % 60 == 0 -> "${intervalMinutes / 60} h"
    else -> "$intervalMinutes m"
}

/** v1.0.0 is Portrait-only; the Portrait/Landscape picker ships with Phase 6. */
internal fun recordModeValue(): String = "Portrait"

/**
 * The swipe-up combined per-session settings sheet (mockups/new_uiux/02-settings-sheet.html).
 * "Recording mode" is a single non-interactive "Portrait" row for v1.0.0 (the picker ships with
 * Phase 6). Each setting row → [onPickRow] with the matching [SheetTarget]; the caller (RecordScreen)
 * opens that param's existing edit sheet via RecordViewModel.openSheet — which renders ON TOP of this
 * sheet (a second ModalBottomSheet); closing it returns here. "Done" → [onDismiss].
 */
@Composable
fun SessionSettingsSheet(
    durationSeconds: Int,
    loopCount: Int,
    intervalMinutes: Int,
    quality: String,
    onPickRow: (SheetTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionLabel("Recording mode")
            Row(
                modifier = Modifier
                    .padding(vertical = 8.dp, horizontal = 4.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(recordModeValue(), style = MaterialTheme.typography.bodyMedium)
                Text("· landscape coming soon", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            SectionLabel("Session settings")
            SettingRow("Clip duration", recordClipValue(durationSeconds)) { onPickRow(SheetTarget.ClipLength) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            SettingRow("Repeats", recordRepeatsValue(loopCount)) { onPickRow(SheetTarget.Repeats) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            SettingRow("Wait between", recordWaitValue(intervalMinutes)) { onPickRow(SheetTarget.Wait) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            SettingRow("Quality", quality) { onPickRow(SheetTarget.Quality) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}
