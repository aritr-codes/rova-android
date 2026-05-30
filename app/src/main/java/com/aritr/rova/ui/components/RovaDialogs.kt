package com.aritr.rova.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun CustomDurationDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var textValue by remember { mutableStateOf("30") }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Duration") },
        text = {
            Column {
                Text("Enter duration in seconds (5 - 300):")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = textValue,
                    onValueChange = {
                        textValue = it
                        val intVal = it.toIntOrNull()
                        isError = intVal == null || intVal !in 5..300
                    },
                    // WCAG 2.2 AA SC 4.1.2 / 1.3.1 (ADR-0020, SHAR-05): the
                    // field needs a programmatic name — without it TalkBack
                    // reads only "edit box".
                    label = { Text("Duration in seconds") },
                    isError = isError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                if (isError) {
                    Text(
                        text = "Value must be between 5 and 300",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        // WCAG 2.2 AA SC 4.1.3 (ADR-0020, SHAR-04): announce the
                        // validation error when it appears without moving focus.
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val intVal = textValue.toIntOrNull()
                    if (intVal != null && intVal in 5..300) {
                        onConfirm(intVal)
                    }
                },
                enabled = !isError
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeProgressSheet(
    progress: Float,
    statusText: String,
    onDismissRequest: () -> Unit // Note: Should probably be non-dismissible usually
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .padding(bottom = 32.dp)
                // WCAG 2.2 AA SC 4.1.3 (ADR-0020, SHAR-07): announce merge
                // phase changes. The CD tracks the coarse statusText, not the
                // per-tick percent, so TalkBack does not chant on every frame.
                .semantics(mergeDescendants = true) {
                    contentDescription = "Merging videos. $statusText"
                    liveRegion = LiveRegionMode.Polite
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Merging Videos",
                style = MaterialTheme.typography.headlineSmall
            )
            
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(80.dp),
                    strokeWidth = 8.dp,
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Text(
                text = "Please don't close the app",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PermissionRationaleDialog(
    onDismiss: () -> Unit,
    onGrant: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permissions Required") },
        text = { Text("Rova needs Camera and Microphone permissions to record videos, and Storage access to save them.") },
        confirmButton = {
            TextButton(onClick = onGrant) {
                Text("Grant")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
