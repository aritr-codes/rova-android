package com.aritr.rova.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.ui.theme.RovaTheme

/**
 * Focused-edit modal bottom sheet shell used by the Record idle screen.
 *
 * Dismiss contract: Done commits, Cancel/Back gesture/Scrim discard.
 * `onDismissRequest` of the underlying [ModalBottomSheet] is wired to
 * [onCancel], NOT [onDone]; back gesture and scrim tap therefore
 * discard the in-sheet draft. Done is the only commit path.
 */
@Composable
fun EditSheetShell(
    title: String,
    subtitle: String?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    doneLabel: String = stringResource(R.string.settings_edit_sheet_done),
    cancelLabel: String = stringResource(R.string.settings_edit_sheet_cancel),
    doneEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .heightIn(min = 240.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(20.dp))
            Box(modifier = Modifier.fillMaxWidth()) { content() }
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onCancel) { Text(cancelLabel) }
                Button(onClick = onDone, enabled = doneEnabled) { Text(doneLabel) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Preview-only renderer of the sheet body. The real [ModalBottomSheet]
 * cannot mount fully inside the Compose Preview host, so the preview
 * shows the inner content arrangement to verify visual rhythm.
 */
@Preview(name = "EditSheetShell content · light", showBackground = true)
@Composable
private fun EditSheetShellPreviewLight() {
    RovaTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(20.dp)) {
                Text("Clip length", style = MaterialTheme.typography.titleMedium) // i18n-opt-out: @Preview sample data
                Spacer(Modifier.height(2.dp))
                Text(
                    "How long each loop records.", // i18n-opt-out: @Preview sample data
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(72.dp)
                ) {}
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = {}) { Text("Cancel") } // i18n-opt-out: @Preview sample data
                    Button(onClick = {}) { Text("Done") } // i18n-opt-out: @Preview sample data
                }
            }
        }
    }
}

@Preview(name = "EditSheetShell content · dark", showBackground = true)
@Composable
private fun EditSheetShellPreviewDark() {
    RovaTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(20.dp)) {
                Text("Repeats", style = MaterialTheme.typography.titleMedium) // i18n-opt-out: @Preview sample data
                Spacer(Modifier.height(2.dp))
                Text(
                    "How many clips this session captures.", // i18n-opt-out: @Preview sample data
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(72.dp)
                ) {}
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = {}) { Text("Cancel") } // i18n-opt-out: @Preview sample data
                    Button(onClick = {}) { Text("Done") } // i18n-opt-out: @Preview sample data
                }
            }
        }
    }
}
