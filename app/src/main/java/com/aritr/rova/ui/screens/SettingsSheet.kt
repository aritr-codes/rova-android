package com.aritr.rova.ui.screens

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aritr.rova.R
import com.aritr.rova.data.PresetSaveValidator
import com.aritr.rova.data.QualityPresets
import com.aritr.rova.data.RovaPreset
import com.aritr.rova.ui.components.focusHighlight
import com.aritr.rova.ui.screens.chrome.NavBarInsetsPx
import com.aritr.rova.ui.screens.chrome.NavEdge
import com.aritr.rova.ui.screens.chrome.systemNavEdge
import com.aritr.rova.ui.theme.RovaTokens
import com.aritr.rova.ui.theme.SettingsSheetTokens

/**
 * Settings surface — orientation-adaptive (ADR-0029 §B3).
 *
 * Portrait → [SettingsBottomSheet]: the camera-peek modal panel (re-skin of
 * `mockups/new_uiux/02-settings-sheet.html`) sliding up from the bottom, with a
 * live-camera peek, drag-to-dismiss, and a Save CTA.
 *
 * Landscape → [SettingsSidePanel]: a STANDARD (non-modal) side sheet hugging the
 * system-nav edge, inboard of the grouped rail, with NO scrim, so the rail
 * (Library + Record FAB) stays visible and tappable while settings are open
 * (§B6). No peek (the visible preview is the context), a compact Done header
 * instead of the oversized Save, horizontal slide from the nav edge, system-back
 * collapses.
 *
 * Both share [SettingsContent] (mode tabs · presets · steppers · quality ·
 * reset). Edits write through immediately; Save / Done / back / drag dismiss.
 * The caller emits this unconditionally and toggles [visible].
 */
@Composable
fun SettingsSheet(
    visible: Boolean,
    durationSeconds: Int,
    loopCount: Int,
    intervalMinutes: Int,
    quality: String,
    currentMode: String,
    editable: Boolean,
    presets: List<RovaPreset>,
    activePresetId: String?,
    onApplyPreset: (RovaPreset) -> Unit,
    onSavePreset: (String) -> Unit,
    onDeletePreset: (RovaPreset) -> Unit,
    statusText: String,
    flashMode: Int,
    flipEnabled: Boolean,
    isFrontCamera: Boolean,
    onCycleFlash: () -> Unit,
    onFlip: () -> Unit,
    onDurationChange: (Int) -> Unit,
    onLoopCountChange: (Int) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onQualityChange: (String) -> Unit,
    onModePick: (String) -> Unit,
    snoozedCount: Int,
    onResetSnoozes: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (landscape) {
        val density = LocalDensity.current
        val layoutDir = LocalLayoutDirection.current
        val navEdge = run {
            val nb = WindowInsets.navigationBars
            systemNavEdge(NavBarInsetsPx(nb.getLeft(density, layoutDir), nb.getRight(density, layoutDir)))
        }
        SettingsSidePanel(
            navEdge = navEdge,
            visible = visible,
            durationSeconds = durationSeconds,
            loopCount = loopCount,
            intervalMinutes = intervalMinutes,
            quality = quality,
            currentMode = currentMode,
            editable = editable,
            presets = presets,
            activePresetId = activePresetId,
            onApplyPreset = onApplyPreset,
            onSavePreset = onSavePreset,
            onDeletePreset = onDeletePreset,
            onDurationChange = onDurationChange,
            onLoopCountChange = onLoopCountChange,
            onIntervalChange = onIntervalChange,
            onQualityChange = onQualityChange,
            onModePick = onModePick,
            snoozedCount = snoozedCount,
            onResetSnoozes = onResetSnoozes,
            onDismiss = onDismiss,
        )
    } else {
        SettingsBottomSheet(
            visible = visible,
            durationSeconds = durationSeconds,
            loopCount = loopCount,
            intervalMinutes = intervalMinutes,
            quality = quality,
            currentMode = currentMode,
            editable = editable,
            presets = presets,
            activePresetId = activePresetId,
            onApplyPreset = onApplyPreset,
            onSavePreset = onSavePreset,
            onDeletePreset = onDeletePreset,
            statusText = statusText,
            flashMode = flashMode,
            flipEnabled = flipEnabled,
            isFrontCamera = isFrontCamera,
            onCycleFlash = onCycleFlash,
            onFlip = onFlip,
            onDurationChange = onDurationChange,
            onLoopCountChange = onLoopCountChange,
            onIntervalChange = onIntervalChange,
            onQualityChange = onQualityChange,
            onModePick = onModePick,
            snoozedCount = snoozedCount,
            onResetSnoozes = onResetSnoozes,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun SettingsBottomSheet(
    visible: Boolean,
    durationSeconds: Int,
    loopCount: Int,
    intervalMinutes: Int,
    quality: String,
    currentMode: String,
    editable: Boolean,
    presets: List<RovaPreset>,
    activePresetId: String?,
    onApplyPreset: (RovaPreset) -> Unit,
    onSavePreset: (String) -> Unit,
    onDeletePreset: (RovaPreset) -> Unit,
    statusText: String,
    flashMode: Int,
    flipEnabled: Boolean,
    isFrontCamera: Boolean,
    onCycleFlash: () -> Unit,
    onFlip: () -> Unit,
    onDurationChange: (Int) -> Unit,
    onLoopCountChange: (Int) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onQualityChange: (String) -> Unit,
    onModePick: (String) -> Unit,
    snoozedCount: Int,
    onResetSnoozes: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        BackHandler(enabled = visible, onBack = onDismiss)
        Box(Modifier.fillMaxSize()) {
            SettingsPeek(
                statusText = statusText,
                flashMode = flashMode,
                flipEnabled = flipEnabled,
                isFrontCamera = isFrontCamera,
                controlsEnabled = editable,
                onCycleFlash = onCycleFlash,
                onFlip = onFlip,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SettingsSheetTokens.peekHeight),
            )
            val panelShape = remember {
                RoundedCornerShape(
                    topStart = SettingsSheetTokens.sheetCornerRadius,
                    topEnd = SettingsSheetTokens.sheetCornerRadius,
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxSize()
                    .padding(top = SettingsSheetTokens.peekHeight)
                    .clip(panelShape)
                    .background(SettingsSheetTokens.sheetFill)
                    .border(1.dp, SettingsSheetTokens.sheetTopStroke, panelShape)
                    .pointerInput(Unit) {
                        var dragTotal = 0f
                        detectVerticalDragGestures(
                            onDragStart = { dragTotal = 0f },
                            onDragEnd = { if (dragTotal > 40f) onDismiss() },
                        ) { _, dragAmount ->
                            dragTotal += dragAmount
                        }
                    }
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = SettingsSheetTokens.sheetPaddingH)
                    .padding(bottom = SettingsSheetTokens.sheetPaddingBottom),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = SettingsSheetTokens.sheetTopPaddingTop,
                            bottom = SettingsSheetTokens.sheetTopPaddingBottom,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(
                                width = SettingsSheetTokens.handleWidth,
                                height = SettingsSheetTokens.handleHeight,
                            )
                            .clip(RoundedCornerShape(SettingsSheetTokens.handleRadius))
                            .background(SettingsSheetTokens.handleColor),
                    )
                }
                SettingsContent(
                    durationSeconds = durationSeconds,
                    loopCount = loopCount,
                    intervalMinutes = intervalMinutes,
                    quality = quality,
                    currentMode = currentMode,
                    editable = editable,
                    presets = presets,
                    activePresetId = activePresetId,
                    onApplyPreset = onApplyPreset,
                    onSavePreset = onSavePreset,
                    onDeletePreset = onDeletePreset,
                    onDurationChange = onDurationChange,
                    onLoopCountChange = onLoopCountChange,
                    onIntervalChange = onIntervalChange,
                    onQualityChange = onQualityChange,
                    onModePick = onModePick,
                    snoozedCount = snoozedCount,
                    onResetSnoozes = onResetSnoozes,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                Spacer(Modifier.height(SettingsSheetTokens.ctaTopMargin))
                val ctaShape = remember { RoundedCornerShape(SettingsSheetTokens.ctaRadius) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ctaShape)
                        .background(SettingsSheetTokens.ctaFill)
                        .border(1.dp, SettingsSheetTokens.ctaStroke, ctaShape)
                        .focusHighlight(ctaShape)
                        .clickable { onDismiss() }
                        .padding(vertical = SettingsSheetTokens.ctaPaddingV),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.settings_sheet_save),
                        style = RovaTokens.sheetCta,
                        color = SettingsSheetTokens.ctaText,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSidePanel(
    navEdge: NavEdge,
    visible: Boolean,
    durationSeconds: Int,
    loopCount: Int,
    intervalMinutes: Int,
    quality: String,
    currentMode: String,
    editable: Boolean,
    presets: List<RovaPreset>,
    activePresetId: String?,
    onApplyPreset: (RovaPreset) -> Unit,
    onSavePreset: (String) -> Unit,
    onDeletePreset: (RovaPreset) -> Unit,
    onDurationChange: (Int) -> Unit,
    onLoopCountChange: (Int) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onQualityChange: (String) -> Unit,
    onModePick: (String) -> Unit,
    snoozedCount: Int,
    onResetSnoozes: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val trailing = navEdge == NavEdge.Trailing
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { if (trailing) it else -it }),
        exit = slideOutHorizontally(targetOffsetX = { if (trailing) it else -it }),
    ) {
        BackHandler(enabled = visible, onBack = onDismiss)
        // Transparent, non-modal container: NO background / NO scrim / NO pointer
        // handler, so taps outside the panel band fall through to the rail beneath
        // (ADR-0029 §B6 — rail + FAB stay live). Only the panel Column captures
        // touches, and it sits inboard of the rail (no overlap).
        Box(Modifier.fillMaxSize()) {
            val panelShape = remember { RoundedCornerShape(SettingsSheetTokens.sheetCornerRadius) }
            Column(
                modifier = Modifier
                    .align(if (trailing) Alignment.CenterEnd else Alignment.CenterStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(
                        start = if (trailing) 0.dp else SettingsSheetTokens.sideSheetRailInset,
                        end = if (trailing) SettingsSheetTokens.sideSheetRailInset else 0.dp,
                        top = SettingsSheetTokens.sidePanelPaddingV,
                        bottom = SettingsSheetTokens.sidePanelPaddingV,
                    )
                    .widthIn(max = SettingsSheetTokens.sideSheetWidth)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .clip(panelShape)
                    .background(SettingsSheetTokens.sheetFill)
                    .border(1.dp, SettingsSheetTokens.sheetTopStroke, panelShape)
                    // ADR-0029 §B6 (codex thread 019eb18d) — the panel surface must be
                    // a touch BARRIER: an opaque background does NOT consume pointer
                    // events, so a tap on a blank panel area would fall through to the
                    // landscape chrome that now stays composed beneath it. Consume every
                    // unhandled pointer change within the panel bounds (inner steppers /
                    // chips / scroll get the Main pass first and consume their own; only
                    // the leftover empty-area events reach this). The OUTER fillMaxSize
                    // wrapper stays transparent so the rail/FAB outside the panel band
                    // remain tappable.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { if (!it.isConsumed) it.consume() }
                            }
                        }
                    }
                    .padding(horizontal = SettingsSheetTokens.sheetPaddingH)
                    .padding(bottom = SettingsSheetTokens.sheetPaddingBottom),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = SettingsSheetTokens.sheetTopPaddingTop,
                            bottom = SettingsSheetTokens.sheetTopPaddingBottom,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.record_nav_settings),
                        style = RovaTokens.sheetCta,
                        color = SettingsSheetTokens.ctaText,
                        modifier = Modifier.weight(1f),
                    )
                    val doneShape = remember { RoundedCornerShape(SettingsSheetTokens.ctaRadius) }
                    Box(
                        modifier = Modifier
                            .clip(doneShape)
                            .background(SettingsSheetTokens.ctaFill)
                            .border(1.dp, SettingsSheetTokens.ctaStroke, doneShape)
                            .focusHighlight(doneShape)
                            .clickable(role = Role.Button) { onDismiss() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.settings_edit_sheet_done),
                            style = RovaTokens.sheetCta,
                            color = SettingsSheetTokens.ctaText,
                        )
                    }
                }
                SettingsContent(
                    durationSeconds = durationSeconds,
                    loopCount = loopCount,
                    intervalMinutes = intervalMinutes,
                    quality = quality,
                    currentMode = currentMode,
                    editable = editable,
                    presets = presets,
                    activePresetId = activePresetId,
                    onApplyPreset = onApplyPreset,
                    onSavePreset = onSavePreset,
                    onDeletePreset = onDeletePreset,
                    onDurationChange = onDurationChange,
                    onLoopCountChange = onLoopCountChange,
                    onIntervalChange = onIntervalChange,
                    onQualityChange = onQualityChange,
                    onModePick = onModePick,
                    snoozedCount = snoozedCount,
                    onResetSnoozes = onResetSnoozes,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

/* ── Camera peek ──────────────────────────────────────────────────────── */

@Composable
private fun SettingsPeek(
    statusText: String,
    flashMode: Int,
    flipEnabled: Boolean,
    isFrontCamera: Boolean,
    controlsEnabled: Boolean,
    onCycleFlash: () -> Unit,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        // Scrim over the live camera (which RecordScreen renders beneath this overlay).
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            SettingsSheetTokens.peekScrimTop,
                            SettingsSheetTokens.peekScrimBottom,
                        ),
                    ),
                ),
        )
        // Mini status pill — bottom-start.
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = SettingsSheetTokens.peekStatusInsetStart,
                    bottom = SettingsSheetTokens.peekStatusInsetBottom,
                )
                .clip(RoundedCornerShape(SettingsSheetTokens.peekStatusRadius))
                .background(SettingsSheetTokens.peekStatusFill)
                .border(
                    1.dp,
                    SettingsSheetTokens.peekStatusStroke,
                    RoundedCornerShape(SettingsSheetTokens.peekStatusRadius),
                )
                .padding(
                    horizontal = SettingsSheetTokens.peekStatusPaddingH,
                    vertical = SettingsSheetTokens.peekStatusPaddingV,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                Modifier
                    .size(SettingsSheetTokens.peekDotSize)
                    .clip(CircleShape)
                    .background(SettingsSheetTokens.peekDotIdle),
            )
            Text(statusText, style = RovaTokens.peekStatus, color = SettingsSheetTokens.peekStatusText)
        }
        // Flash + flip — reuse the Phase-2 shared chrome control.
        RecordCameraControls(
            flashMode = flashMode,
            onCycleFlash = onCycleFlash,
            onFlip = onFlip,
            enabled = controlsEnabled,
            flipEnabled = flipEnabled,
            isFrontCamera = isFrontCamera,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(end = SettingsSheetTokens.camControlsInsetEnd, top = 7.dp),
        )
    }
}

/* ── Shared settings body (ADR-0029 §B3) ──────────────────────────────── */

/**
 * The scrollable settings body shared by [SettingsBottomSheet] (portrait) and
 * [SettingsSidePanel] (landscape): mode tabs · presets · steppers · quality ·
 * reset-snoozes, plus the preset name / delete dialogs. Owns its own scroll so
 * either host can constrain it with a `weight(1f)` [modifier] and the body
 * scrolls when the host's height is short (landscape). Edits write through
 * immediately via the callbacks.
 */
@Composable
private fun SettingsContent(
    durationSeconds: Int,
    loopCount: Int,
    intervalMinutes: Int,
    quality: String,
    currentMode: String,
    editable: Boolean,
    presets: List<RovaPreset>,
    activePresetId: String?,
    onApplyPreset: (RovaPreset) -> Unit,
    onSavePreset: (String) -> Unit,
    onDeletePreset: (RovaPreset) -> Unit,
    onDurationChange: (Int) -> Unit,
    onLoopCountChange: (Int) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onQualityChange: (String) -> Unit,
    onModePick: (String) -> Unit,
    snoozedCount: Int,
    onResetSnoozes: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var namingVisible by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RovaPreset?>(null) }
    val customNames = presets.filter { !it.isBuiltIn }
    val bodyScroll = rememberScrollState()
    Column(modifier = modifier.verticalScroll(bodyScroll)) {
        SheetSectionLabel(stringResource(R.string.settings_sheet_section_recording_mode))
        Spacer(Modifier.height(SettingsSheetTokens.sectionLabelGap))
        ModeTabs(currentMode = currentMode, enabled = editable, onPick = onModePick)
        Spacer(Modifier.height(SettingsSheetTokens.modeTabsBottomMargin))

        SheetSectionLabel(stringResource(R.string.settings_sheet_section_presets))
        Spacer(Modifier.height(SettingsSheetTokens.sectionLabelGap))
        PresetSection(
            presets = presets,
            activePresetId = activePresetId,
            enabled = editable,
            onApply = onApplyPreset,
            onRequestSave = { namingVisible = true },
            onRequestDelete = { pendingDelete = it },
        )
        Spacer(Modifier.height(SettingsSheetTokens.modeTabsBottomMargin))

        SheetSectionLabel(stringResource(R.string.settings_sheet_section_settings))
        Spacer(Modifier.height(SettingsSheetTokens.sectionLabelGap))

        StepperRow(
            label = stringResource(R.string.settings_sheet_clip_duration),
            value = recordClipValue(durationSeconds),
            enabled = editable,
            atMin = RecordSettingBounds.clipAtMin(durationSeconds),
            atMax = RecordSettingBounds.clipAtMax(durationSeconds),
            onStep = { dir -> onDurationChange(RecordSettingBounds.stepClip(durationSeconds, dir)) },
        )
        SheetRowDivider()
        StepperRow(
            label = stringResource(R.string.settings_sheet_repeats),
            value = recordRepeatsStepperValue(loopCount),
            enabled = editable,
            atMin = RecordSettingBounds.repeatsAtMin(loopCount),
            atMax = RecordSettingBounds.repeatsAtMax(loopCount),
            onStep = { dir -> onLoopCountChange(RecordSettingBounds.stepRepeats(loopCount, dir)) },
        )
        SheetRowDivider()
        StepperRow(
            label = stringResource(R.string.settings_sheet_wait_between),
            value = recordWaitValue(intervalMinutes),
            enabled = editable,
            atMin = RecordSettingBounds.waitAtMin(intervalMinutes),
            atMax = RecordSettingBounds.waitAtMax(intervalMinutes),
            onStep = { dir -> onIntervalChange(RecordSettingBounds.stepWait(intervalMinutes, dir)) },
        )
        SheetRowDivider()
        QualityRow(quality = quality, enabled = editable, onPick = onQualityChange)

        if (onResetSnoozes != null) {
            SheetRowDivider()
            ResetSnoozesRow(count = snoozedCount, onClick = onResetSnoozes)
        }
    }
    if (namingVisible) {
        PresetNameDialog(
            existingCustoms = customNames,
            onDismiss = { namingVisible = false },
            onConfirm = { name ->
                namingVisible = false
                onSavePreset(name)
            },
        )
    }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.preset_delete_title)) },
            text = { Text(stringResource(R.string.preset_delete_body, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDeletePreset(target)
                }) {
                    Text(
                        text = stringResource(R.string.preset_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

/* ── Pieces ───────────────────────────────────────────────────────────── */

@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = RovaTokens.sheetSectionLabel,
        color = SettingsSheetTokens.sectionLabelColor,
    )
}

@Composable
private fun SheetRowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(SettingsSheetTokens.rowDivider),
    )
}

private enum class SheetModeTab(@StringRes val labelRes: Int, val value: String) {
    Portrait(R.string.settings_sheet_mode_portrait, "Portrait"),
    Landscape(R.string.settings_sheet_mode_landscape, "Landscape"),
    PortraitLandscape(R.string.settings_sheet_mode_pl, "PortraitLandscape"),
}

@Composable
private fun ModeTabs(currentMode: String, enabled: Boolean, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingsSheetTokens.modeTabsRadius))
            .background(SettingsSheetTokens.modeTabsTrackFill)
            .padding(SettingsSheetTokens.modeTabsPadding),
        horizontalArrangement = Arrangement.spacedBy(SettingsSheetTokens.modeTabsGap),
    ) {
        SheetModeTab.entries.forEach { tab ->
            val isActive = currentMode == tab.value
            val tabShape = RoundedCornerShape(SettingsSheetTokens.modeTabRadius)
            val tabModifier = Modifier
                .weight(1f)
                .clip(tabShape)
                .let {
                    if (isActive) {
                        it.shadow(1.dp, tabShape).background(SettingsSheetTokens.modeTabActiveFill)
                    } else {
                        it
                    }
                }
                .let { if (enabled && !isActive) it.focusHighlight(tabShape).clickable { onPick(tab.value) } else it }
                .padding(
                    horizontal = SettingsSheetTokens.modeTabPaddingH,
                    vertical = SettingsSheetTokens.modeTabPaddingV,
                )
            val textColor = when {
                isActive -> SettingsSheetTokens.modeTabActiveText
                !enabled -> SettingsSheetTokens.modeTabDisabledText
                else -> SettingsSheetTokens.modeTabIdleText
            }
            Box(modifier = tabModifier, contentAlignment = Alignment.Center) {
                Text(stringResource(tab.labelRes), style = RovaTokens.sheetModeTab, color = textColor)
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    enabled: Boolean,
    atMin: Boolean,
    atMax: Boolean,
    onStep: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SettingsSheetTokens.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = RovaTokens.sheetRowLabel,
            color = SettingsSheetTokens.rowLabelText,
            modifier = Modifier.weight(1f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SettingsSheetTokens.stepperGap),
        ) {
            StepButton("−", enabled = enabled && !atMin, onClick = { onStep(-1) })
            Text(
                value,
                style = RovaTokens.sheetStepValue,
                color = SettingsSheetTokens.stepValText,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = SettingsSheetTokens.stepValMinWidth),
            )
            StepButton("+", enabled = enabled && !atMax, onClick = { onStep(+1) })
        }
    }
}

@Composable
private fun StepButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(SettingsSheetTokens.stepBtnRadius)
    Box(
        modifier = Modifier
            .size(SettingsSheetTokens.stepBtnSize)
            .clip(shape)
            .background(SettingsSheetTokens.stepBtnFill)
            .border(1.dp, SettingsSheetTokens.stepBtnStroke, shape)
            .then(if (enabled) Modifier.focusHighlight(shape).clickable { onClick() } else Modifier)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = SettingsSheetTokens.stepBtnGlyph,
            fontSize = 16.sp,
            fontWeight = FontWeight.Light,
        )
    }
}

@Composable
private fun QualityRow(quality: String, enabled: Boolean, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SettingsSheetTokens.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.settings_sheet_quality),
            style = RovaTokens.sheetRowLabel,
            color = SettingsSheetTokens.rowLabelText,
            modifier = Modifier.weight(1f),
        )
        val current = QualityPresets.canonicalizeOrDefault(quality)
        Row(horizontalArrangement = Arrangement.spacedBy(SettingsSheetTokens.chipGroupGap)) {
            QualityPresets.PICKER_ORDER.forEach { option ->
                QualityChip(
                    label = option,
                    selected = option == current,
                    enabled = enabled,
                    onClick = { onPick(option) },
                )
            }
        }
    }
}

@Composable
private fun QualityChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(SettingsSheetTokens.chipRadius)
    val fill = if (selected) SettingsSheetTokens.chipOnFill else Color.Transparent
    val stroke = if (selected) SettingsSheetTokens.chipOnStroke else SettingsSheetTokens.chipOffStroke
    val textColor = if (selected) SettingsSheetTokens.chipOnText else SettingsSheetTokens.chipOffText
    Box(
        modifier = Modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, stroke, shape)
            .then(if (enabled) Modifier.focusHighlight(shape).clickable { onClick() } else Modifier)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(
                horizontal = SettingsSheetTokens.chipPaddingH,
                vertical = SettingsSheetTokens.chipPaddingV,
            ),
    ) {
        Text(label, style = RovaTokens.sheetChip, color = textColor)
    }
}

/* ── Presets (ADR-0026) ───────────────────────────────────────────────── */

/**
 * Preset chips — one tap applies the whole config bundle via [onApply]. Wraps
 * onto multiple lines (preset names are longer than the quality chips), so a
 * [FlowRow] rather than the horizontal-scroll row this replaced. Disabled while
 * a session is active (`enabled = editable`), mirroring the steppers below.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetSection(
    presets: List<RovaPreset>,
    activePresetId: String?,
    enabled: Boolean,
    onApply: (RovaPreset) -> Unit,
    onRequestSave: () -> Unit,
    onRequestDelete: (RovaPreset) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SettingsSheetTokens.chipGroupGap),
        verticalArrangement = Arrangement.spacedBy(SettingsSheetTokens.chipGroupGap),
    ) {
        presets.forEach { preset ->
            PresetSheetChip(
                preset = preset,
                selected = preset.id == activePresetId,
                enabled = enabled,
                onClick = { onApply(preset) },
                // Long-press deletes user customs only; built-ins are read-only.
                onLongClick = if (!preset.isBuiltIn) {
                    { onRequestDelete(preset) }
                } else {
                    null
                },
            )
        }
        // "+ Save" appears only when the current config matches no preset
        // (activePresetId == null = genuinely Custom) and the sheet is editable.
        if (activePresetId == null && enabled) {
            SavePresetChip(onClick = onRequestSave)
        }
    }
}

/**
 * Sheet-native preset chip — same fill/stroke styling as [QualityChip], plus a
 * leading check icon when selected so the selected state is conveyed by more
 * than colour (WCAG 1.4.1, ADR-0020). `selected` semantics + a spoken
 * [presetSpokenDescription] make it screen-reader complete; a >=48dp min height
 * keeps the touch target comfortable. Custom (non-built-in) chips support
 * long-press-to-delete via [onLongClick] + a TalkBack custom action label.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetSheetChip(
    preset: RovaPreset,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(SettingsSheetTokens.chipRadius)
    val fill = if (selected) SettingsSheetTokens.chipOnFill else Color.Transparent
    val stroke = if (selected) SettingsSheetTokens.chipOnStroke else SettingsSheetTokens.chipOffStroke
    val textColor = if (selected) SettingsSheetTokens.chipOnText else SettingsSheetTokens.chipOffText
    val cd = presetSpokenDescription(preset)
    // The long-press label surfaces delete as a TalkBack/Switch/Voice custom
    // action — the non-gesture equivalent required by WCAG SC 2.5.1 / 2.1.1
    // (codex a11y review). Sighted-touch discoverability is an accepted tradeoff
    // (delete is rare, destructive, and confirmed).
    val deleteLabel = stringResource(R.string.preset_chip_delete_action)
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, stroke, shape)
            .then(
                if (enabled) {
                    Modifier
                        .focusHighlight(shape)
                        .combinedClickable(
                            role = Role.Button,
                            onClick = onClick,
                            onLongClick = onLongClick,
                            onLongClickLabel = if (onLongClick != null) deleteLabel else null,
                        )
                } else {
                    Modifier
                },
            )
            .alpha(if (enabled) 1f else 0.5f)
            .semantics {
                this.selected = selected
                contentDescription = cd
                if (!enabled) disabled()
            }
            .padding(
                horizontal = SettingsSheetTokens.chipPaddingH,
                vertical = SettingsSheetTokens.chipPaddingV,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(preset.name, style = RovaTokens.sheetChip, color = textColor)
    }
}

/**
 * The "+ Save" affordance — styled like an unselected [PresetSheetChip] with a
 * leading Add icon. Shown only when the config matches no preset; tapping opens
 * the naming dialog. (ADR-0026.)
 */
@Composable
private fun SavePresetChip(onClick: () -> Unit) {
    val shape = RoundedCornerShape(SettingsSheetTokens.chipRadius)
    val textColor = SettingsSheetTokens.chipOffText
    val cd = stringResource(R.string.preset_save_chip_cd)
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(Color.Transparent)
            .border(1.dp, SettingsSheetTokens.chipOffStroke, shape)
            .focusHighlight(shape)
            .clickable(role = Role.Button) { onClick() }
            .semantics { contentDescription = cd }
            .padding(
                horizontal = SettingsSheetTokens.chipPaddingH,
                vertical = SettingsSheetTokens.chipPaddingV,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(16.dp),
        )
        Text(stringResource(R.string.preset_save_chip), style = RovaTokens.sheetChip, color = textColor)
    }
}

/**
 * The screen-reader phrase for a preset chip, e.g. "Standard preset, 30 second
 * clips, every 2 minutes, repeats 20 times, FHD". Built from the same
 * localized `preset_cd_*` resources the old idle PresetRow used.
 */
@Composable
private fun presetSpokenDescription(p: RovaPreset): String {
    val clipPhrase = pluralStringResource(R.plurals.preset_cd_clip_seconds, p.duration, p.duration)
    val waitPhrase = if (p.interval <= 0) {
        stringResource(R.string.preset_cd_no_gap)
    } else {
        pluralStringResource(R.plurals.preset_cd_every_minutes, p.interval, p.interval)
    }
    val repeatsPhrase = if (p.loopCount < 0) {
        stringResource(R.string.preset_cd_until_stop)
    } else {
        pluralStringResource(R.plurals.preset_cd_repeats_times, p.loopCount, p.loopCount)
    }
    return stringResource(R.string.preset_cd_full, p.name, clipPhrase, waitPhrase, repeatsPhrase, p.resolution)
}

/**
 * Naming dialog for saving the current config as a custom preset. Live-validates
 * via [PresetSaveValidator]: OK is disabled until the name is valid, and the
 * error announces through a polite live region (WCAG SC 4.1.3, ADR-0020). The
 * field gets a programmatic label (SC 4.1.2). An empty untouched field shows no
 * error (OK simply stays disabled).
 */
@Composable
private fun PresetNameDialog(
    existingCustoms: List<RovaPreset>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val result = PresetSaveValidator.validateName(text, existingCustoms)
    val isOk = result == PresetSaveValidator.Result.Ok
    val errorRes: Int? = when (result) {
        PresetSaveValidator.Result.DuplicateName -> R.string.preset_name_error_duplicate
        PresetSaveValidator.Result.TooLong -> R.string.preset_name_error_too_long
        PresetSaveValidator.Result.Blank -> if (text.isNotEmpty()) R.string.preset_name_error_blank else null
        PresetSaveValidator.Result.Ok -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_name_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.preset_name_field_label)) },
                    isError = errorRes != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                if (errorRes != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(errorRes),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (isOk) onConfirm(text.trim()) }, enabled = isOk) {
                Text(stringResource(R.string.dialog_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

/* ── Reset snoozed warnings (Phase 4.1c) ──────────────────────────────── */

/**
 * One-line clickable row that clears the persisted snooze set via
 * [WarningCenterViewModel.clearSnoozes]. Surfaced only when the caller
 * passes a non-null `onClick` (i.e. the set is non-empty), so there is
 * no dead-end "Reset (0)" affordance.
 *
 * Visually mirrors the existing settings rows: title on the left at
 * `sheetRowLabel` style, subtitle directly under the title with a muted
 * alpha, no value/stepper on the right.
 */
@Composable
private fun ResetSnoozesRow(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusHighlight(RectangleShape)
            .clickable { onClick() }
            .padding(vertical = SettingsSheetTokens.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.settings_sheet_reset_snoozes),
                style = RovaTokens.sheetRowLabel,
                color = SettingsSheetTokens.rowLabelText,
            )
            Text(
                pluralStringResource(R.plurals.settings_warnings_hidden_count, count, count),
                style = RovaTokens.sheetRowLabel,
                color = SettingsSheetTokens.rowLabelText.copy(alpha = 0.55f),
                fontSize = 11.sp,
            )
        }
    }
}
