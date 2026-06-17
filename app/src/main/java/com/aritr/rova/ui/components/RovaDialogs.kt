package com.aritr.rova.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.ui.theme.GlassRole
import com.aritr.rova.ui.theme.GlassSurface
import com.aritr.rova.ui.theme.LocalGlassEnvironment

/**
 * Branded app dialog (owner 2026-06-17 — stock M3 [AlertDialog] looked generic). One reusable
 * scaffold so every dialog joins the Liquid Glass identity (ADR-0028): a palette-tinted near-opaque
 * [GlassSurface] (role=Dialog) with a real cast shadow, app typography, and royal-violet accent-tinted
 * actions instead of stock Material blue. [BasicAlertDialog] supplies the modal window + scrim +
 * outside/back dismiss; this owns the surface + layout.
 *
 * - [text] = a plain body line; [content] = a custom body slot (text fields, validation, etc.). Either,
 *   both, or neither. Custom content inherits `LocalContentColor = palette.textHigh`.
 * - [confirmEnabled] gates the confirm action; [destructive] tints it (and any [icon]) in the error
 *   color; [confirmFilled] renders confirm as a dominant filled button (critical modals only).
 * - [dismissText] null → no dismiss button (e.g. force-action modals). Dismiss reads in [textDim] so the
 *   confirm action stays the visual lead.
 * - A11y: the surface carries `paneTitle`; the title `Text` is a heading (SC 1.3.1 / 4.1.2, ADR-0020).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RovaAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    confirmFilled: Boolean = false,
    dismissText: String? = null,
    onDismiss: () -> Unit = onDismissRequest,
    icon: ImageVector? = null,
    text: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val palette = LocalGlassEnvironment.current.palette
    val shape = RoundedCornerShape(28.dp)
    val accentColor = if (destructive) MaterialTheme.colorScheme.error else palette.accent
    BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier) {
        Box(Modifier.shadow(12.dp, shape, clip = false)) {
            GlassSurface(
                role = GlassRole.Dialog,
                shape = shape,
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 560.dp)
                    .semantics { paneTitle = title },
            ) {
                CompositionLocalProvider(LocalContentColor provides palette.textHigh) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                        if (icon != null) {
                            Icon(icon, null, tint = accentColor, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(12.dp))
                        }
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textHigh,
                            modifier = Modifier.semantics { heading() },
                        )
                        if (text != null) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.textDim,
                            )
                        }
                        if (content != null) {
                            Spacer(Modifier.height(12.dp))
                            content()
                        }
                        Spacer(Modifier.height(20.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (dismissText != null) {
                                TextButton(
                                    onClick = onDismiss,
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = palette.textDim,
                                    ),
                                ) { Text(dismissText) }
                                Spacer(Modifier.width(8.dp))
                            }
                            if (confirmFilled) {
                                Button(
                                    onClick = onConfirm,
                                    enabled = confirmEnabled,
                                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                                ) { Text(confirmText) }
                            } else {
                                TextButton(
                                    onClick = onConfirm,
                                    enabled = confirmEnabled,
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = accentColor,
                                        disabledContentColor = palette.textHigh.copy(alpha = 0.38f),
                                    ),
                                ) { Text(confirmText) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CustomDurationDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var textValue by remember { mutableStateOf("30") }
    var isError by remember { mutableStateOf(false) }

    RovaAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.dialog_custom_duration_title),
        confirmText = stringResource(R.string.dialog_ok),
        confirmEnabled = !isError,
        onConfirm = {
            val intVal = textValue.toIntOrNull()
            if (intVal != null && intVal in 5..300) {
                onConfirm(intVal)
            }
        },
        dismissText = stringResource(R.string.dialog_cancel),
        content = {
            Text(stringResource(R.string.dialog_custom_duration_message))
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
                label = { Text(stringResource(R.string.dialog_custom_duration_field_label)) },
                isError = isError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            if (isError) {
                Text(
                    text = stringResource(R.string.dialog_custom_duration_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    // WCAG 2.2 AA SC 4.1.3 (ADR-0020, SHAR-04): announce the
                    // validation error when it appears without moving focus.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeProgressSheet(
    progress: Float,
    statusText: String,
    onDismissRequest: () -> Unit // Note: Should probably be non-dismissible usually
) {
    val mergeProgressCd = stringResource(R.string.dialog_merge_progress_cd, statusText)
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
                    contentDescription = mergeProgressCd
                    liveRegion = LiveRegionMode.Polite
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.dialog_merge_progress_title),
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
                text = stringResource(R.string.dialog_merge_progress_keep_open),
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
    RovaAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.dialog_permissions_title),
        text = stringResource(R.string.dialog_permissions_message),
        confirmText = stringResource(R.string.dialog_permissions_grant),
        onConfirm = onGrant,
        dismissText = stringResource(R.string.dialog_cancel),
    )
}
