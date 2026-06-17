package com.aritr.rova.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.ui.theme.DialogActionColors
import com.aritr.rova.ui.theme.GlassRole
import com.aritr.rova.ui.theme.GlassSurface
import com.aritr.rova.ui.theme.LocalGlassEnvironment
import kotlin.math.roundToInt

/**
 * Branded app dialog (owner 2026-06-17 — stock M3 [AlertDialog] looked generic; the flat first pass
 * "looked half baked"). One reusable scaffold so every dialog joins the Liquid Glass identity
 * (ADR-0028) with REAL depth, not a recolored box. Premium cues (2026 research + codex 2026-06-17):
 *   - 32dp concentric corners + a soft ambient shadow.
 *   - A palette-tinted [GlassSurface] (role=Dialog) base, overlaid with a faint top "lift" wash and a
 *     lit glass RIM (gradient `edgeTop → edge`, brighter at the top) — depth without a second flat box.
 *   - An optional header icon in a soft accent chip beside a [headlineSmall] title.
 *   - The primary action is a FILLED accent-gradient pill (the focal point); its label color is WCAG-
 *     checked per palette via [DialogActionColors] (neon accents like Meadow stay AA, SC 1.4.3). Dismiss
 *     is a ghost [textDim] button so hierarchy matches consequence; [destructive] paints the CTA + icon
 *     in the error color.
 *
 * - [text] = a plain body line; [content] = a custom body slot (text fields, spec rows, …). Custom
 *   content inherits `LocalContentColor = palette.textHigh`.
 * - [confirmText] null → no primary button. [dismissText] null → no ghost button. [dismissIcon] true →
 *   a top-right X (wired to [onDismissRequest]) — the dismiss affordance for informational dialogs that
 *   have no actions (e.g. "Recording details", owner asked to drop the bottom "Close").
 * - A11y: surface carries `paneTitle`; title is a heading; the X is a 40dp [IconButton] with a "Close"
 *   contentDescription; the CTA pill announces as a Button (SC 1.3.1 / 2.5.8 / 4.1.2, ADR-0020).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RovaAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmText: String? = null,
    onConfirm: () -> Unit = {},
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    dismissText: String? = null,
    onDismiss: () -> Unit = onDismissRequest,
    dismissIcon: Boolean = false,
    icon: ImageVector? = null,
    text: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val palette = LocalGlassEnvironment.current.palette
    val shape = RoundedCornerShape(32.dp)
    val accentColor = if (destructive) MaterialTheme.colorScheme.error else palette.accent

    // Depth overlays (drawn over the GlassSurface fill, under the content): a top "lift" wash + one lit
    // glass rim. Light palette (Daylight) lifts with black; dark palettes lift with white.
    val liftColor = if (palette.isLight) Color.Black.copy(alpha = 0.035f) else Color.White.copy(alpha = 0.06f)
    val lift = Brush.verticalGradient(
        0f to liftColor,
        0.55f to Color.Transparent,
        1f to Color.Transparent,
    )
    val rim = Brush.verticalGradient(
        listOf(palette.edgeTop, palette.edge.copy(alpha = palette.edge.alpha * 0.35f)),
    )

    BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier) {
        Box(Modifier.shadow(22.dp, shape, clip = false)) {
            GlassSurface(
                role = GlassRole.Dialog,
                shape = shape,
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 560.dp)
                    .semantics { paneTitle = title },
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(shape)
                        .background(lift)
                        .border(1.dp, rim, shape),
                )
                CompositionLocalProvider(LocalContentColor provides palette.textHigh) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 22.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (icon != null) {
                                Box(
                                    Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(accentColor.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(icon, null, tint = accentColor, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(14.dp))
                            }
                            Text(
                                title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textHigh,
                                modifier = Modifier.weight(1f).semantics { heading() },
                            )
                            if (dismissIcon) {
                                IconButton(onClick = onDismissRequest, modifier = Modifier.size(40.dp)) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.dialog_close),
                                        tint = palette.textDim,
                                    )
                                }
                            }
                        }
                        if (text != null) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.textDim,
                            )
                        }
                        if (content != null) {
                            Spacer(Modifier.height(16.dp))
                            content()
                        }
                        if (confirmText != null || dismissText != null) {
                            Spacer(Modifier.height(22.dp))
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
                                    Spacer(Modifier.width(10.dp))
                                }
                                if (confirmText != null) {
                                    DialogCtaButton(
                                        label = confirmText,
                                        enabled = confirmEnabled,
                                        startColor = accentColor,
                                        endColor = if (destructive) accentColor else palette.accent2,
                                        onClick = onConfirm,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Premium filled CTA pill — accent gradient with a WCAG-checked label ([DialogActionColors]). */
@Composable
private fun DialogCtaButton(
    label: String,
    enabled: Boolean,
    startColor: Color,
    endColor: Color,
    onClick: () -> Unit,
) {
    fun Color.toRgb() = intArrayOf(
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt(),
    )
    val cta = remember(startColor, endColor) {
        DialogActionColors.resolve(startColor.toRgb(), endColor.toRgb())
    }
    val fill = Brush.horizontalGradient(
        listOf(
            Color(cta.start[0], cta.start[1], cta.start[2]),
            Color(cta.end[0], cta.end[1], cta.end[2]),
        ),
    )
    val labelColor = if (cta.contentWhite) Color.White else Color(0xFF0E1116)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .alpha(if (enabled) 1f else 0.4f)
            .background(fill, RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = labelColor,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
        )
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
