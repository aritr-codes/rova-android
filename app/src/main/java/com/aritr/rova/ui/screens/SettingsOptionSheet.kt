package com.aritr.rova.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.components.focusHighlight

/**
 * B1 — generic single-select bottom sheet. One row per option with a trailing
 * check on the selected one. Each row is a `selectable(role = RadioButton)`
 * node with a merged content description, and shows a focus ring for D-pad
 * (WCAG 2.2 AA SC 4.1.2 / 2.4.7, ADR-0020).
 *
 * @param optionLabel  Human label for an option (e.g. { it } for resolution).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SettingsOptionSheet(
    title: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .semantics { heading() },
            )
            options.forEach { option ->
                val isSelected = option == selected
                val label = optionLabel(option)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusHighlight(RectangleShape)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onPick(option) },
                        )
                        .semantics {
                            contentDescription =
                                if (isSelected) "$label, selected" else label
                        }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
