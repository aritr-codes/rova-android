package com.aritr.rova.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.components.focusHighlight

/**
 * B1 — generic `−` / value / `+` bottom sheet for a single Int recording
 * default (clip duration, interval, loops). All clamp/step logic is supplied
 * by the caller from [RecordSettingBounds]; this composable owns presentation
 * + accessibility only.
 *
 * @param title        Sheet heading (e.g. "Clip duration").
 * @param valueLabel   Formatted current value (e.g. recordClipValue(value)).
 * @param atMin        Disable the `−` button (caller: RecordSettingBounds.*AtMin).
 * @param atMax        Disable the `+` button (caller: RecordSettingBounds.*AtMax).
 * @param onStep       Invoked with -1 / +1; caller maps via RecordSettingBounds.step*.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsStepperSheet(
    title: String,
    valueLabel: String,
    atMin: Boolean,
    atMax: Boolean,
    onStep: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
                    .semantics { heading() },
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                StepGlyphButton(
                    glyph = "−",
                    enabled = !atMin,
                    contentDescription = "Decrease $title",
                    onClick = { onStep(-1) },
                )
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(min = 120.dp)
                        // Anti-chant: announces the new discrete value on change,
                        // not a per-tick stream (the value only moves on a tap).
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                StepGlyphButton(
                    glyph = "+",
                    enabled = !atMax,
                    contentDescription = "Increase $title",
                    onClick = { onStep(+1) },
                )
            }
        }
    }
}

@Composable
private fun StepGlyphButton(
    glyph: String,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .then(
                if (enabled) {
                    Modifier
                        .focusHighlight(shape)
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .alpha(if (enabled) 1f else 0.4f)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
