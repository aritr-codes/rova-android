package com.aritr.rova.ui.library.components

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.aritr.rova.ui.components.RovaAlertDialog

/**
 * spec §5.3 — rename dialog. Seeds the field with [currentTitle]; confirm calls [onRename] with the new
 * text (blank clears the custom title — the row falls back to SmartTitle, handled in the VM). Confirm /
 * Dismiss are Button-role TextButtons. Labels are passed in (en/es resources).
 */
@Composable
fun LibraryRenameDialog(
    currentTitle: String,
    titleLabel: String,
    fieldHint: String,
    confirmLabel: String,
    cancelLabel: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(currentTitle) }
    RovaAlertDialog(
        onDismissRequest = onDismiss,
        title = titleLabel,
        confirmText = confirmLabel,
        onConfirm = {
            onRename(text)
            onDismiss()
        },
        dismissText = cancelLabel,
        content = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(fieldHint) },
            )
        },
    )
}
