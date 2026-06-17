package com.aritr.rova.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType

/**
 * Lightweight manual numeric entry for the settings steppers (owner request 2026-06-17 — stepping
 * to large clip counts / waits is tedious). Pre-filled with the current value; the number keyboard
 * is forced; [onConfirm] receives the raw parsed Int (the caller clamps via RecordSettingBounds).
 * Invalid/blank input disables the confirm button. The +/− steppers remain for quick adjustments.
 *
 * [initialValue] may be null → the field starts empty (e.g. Repeats when currently ∞/continuous, so
 * confirming can never silently turn ∞ into a finite count — the user must type one).
 */
@Composable
fun NumericEntryDialog(
    title: String,
    initialValue: Int?,
    hint: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialValue?.toString() ?: "") }
    val parsed = text.trim().toIntOrNull()
    RovaAlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmText = confirmLabel,
        confirmEnabled = parsed != null,
        onConfirm = { parsed?.let(onConfirm) },
        dismissText = cancelLabel,
        content = {
            OutlinedTextField(
                value = text,
                onValueChange = { new -> text = new.filter { it.isDigit() }.take(4) },
                singleLine = true,
                label = { Text(hint) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
    )
}
