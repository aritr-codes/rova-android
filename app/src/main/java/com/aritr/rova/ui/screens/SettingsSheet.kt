package com.aritr.rova.ui.screens

import android.content.res.Configuration
import android.view.Surface
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aritr.rova.R
import com.aritr.rova.data.PresetSaveValidator
import com.aritr.rova.data.QualityPresets
import com.aritr.rova.data.RovaPreset
import com.aritr.rova.ui.components.focusHighlight
import com.aritr.rova.ui.screens.chrome.DeviceLandscape
import com.aritr.rova.ui.screens.chrome.NavEdge
import com.aritr.rova.ui.screens.chrome.clusterEdge
import com.aritr.rova.ui.screens.chrome.landscapeSense
import com.aritr.rova.ui.theme.LocalGlassEnvironment
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
 * system-nav edge, with NO scrim so the live preview remains visible. The panel
 * is a portrait sheet rotated: 380 dp silhouette cap, grip + title + stacked
 * rows + slimmed Save CTA, scrolling body. Slides in from the cluster edge;
 * system-back collapses.
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
    orientationPolicy: String,
    orientationLockRotation: Int,
    orientationEnabled: Boolean,
    currentDeviceRotation: Int?,
    onOrientationPick: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (landscape) {
        // PR-β′ — side-anchor + width derive from the display-rotation sense (spec §4).
        @Suppress("DEPRECATION")
        val rot = LocalView.current.display?.rotation ?: Surface.ROTATION_0
        val sense = landscapeSense(rot) ?: DeviceLandscape.A
        SettingsSidePanel(
            sense = sense,
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
            orientationPolicy = orientationPolicy,
            orientationLockRotation = orientationLockRotation,
            orientationEnabled = orientationEnabled,
            currentDeviceRotation = currentDeviceRotation,
            onOrientationPick = onOrientationPick,
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
            orientationPolicy = orientationPolicy,
            orientationLockRotation = orientationLockRotation,
            orientationEnabled = orientationEnabled,
            currentDeviceRotation = currentDeviceRotation,
            onOrientationPick = onOrientationPick,
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
    orientationPolicy: String,
    orientationLockRotation: Int,
    orientationEnabled: Boolean,
    currentDeviceRotation: Int?,
    onOrientationPick: (String, Int) -> Unit,
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
                    orientationPolicy = orientationPolicy,
                    orientationLockRotation = orientationLockRotation,
                    orientationEnabled = orientationEnabled,
                    currentDeviceRotation = currentDeviceRotation,
                    onOrientationPick = onOrientationPick,
                    showTitle = true,
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
                        .clickable(role = Role.Button) { onDismiss() }
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
    sense: DeviceLandscape,
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
    orientationPolicy: String,
    orientationLockRotation: Int,
    orientationEnabled: Boolean,
    currentDeviceRotation: Int?,
    onOrientationPick: (String, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val trailing = clusterEdge(sense) == NavEdge.Trailing
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { if (trailing) it else -it }),
        exit = slideOutHorizontally(targetOffsetX = { if (trailing) it else -it }),
    ) {
        BackHandler(enabled = visible, onBack = onDismiss)
        // Rotate-spec §11 D2 (2026-06-11) — the portrait bottom sheet, ROTATED to the
        // cluster edge: full height, width = the portrait SILHOUETTE (sideSheetWidth
        // cap; the Phase-A availableWidth − peek derivation read as a desktop panel —
        // owner NO-GO). Live preview fills the far side — no added scrim (§6.6). Same
        // composition as portrait: grip + title + stacked rows + Save CTA;
        // SettingsContent scrolls (Repeats/Wait/Quality/Save reachable below the fold).
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val panelWidth = (maxWidth - SettingsSheetTokens.peekHeight)
                .coerceAtMost(SettingsSheetTokens.sideSheetWidth)
                .coerceAtLeast(SettingsSheetTokens.sideSheetMinWidth)
            val panelShape = remember { RoundedCornerShape(SettingsSheetTokens.sheetCornerRadius) }
            Column(
                modifier = Modifier
                    .align(if (trailing) Alignment.CenterEnd else Alignment.CenterStart)
                    .width(panelWidth)
                    .fillMaxHeight()
                    .clip(panelShape)
                    .background(SettingsSheetTokens.sheetFill)
                    .border(1.dp, SettingsSheetTokens.sheetTopStroke, panelShape)
                    // Touch barrier: absorb stray taps on blank panel area; drags/scroll
                    // + child taps still reach children (detectTapGestures only).
                    .pointerInput(Unit) { detectTapGestures { } }
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = SettingsSheetTokens.sheetPaddingH)
                    .padding(bottom = SettingsSheetTokens.sheetPaddingBottom),
            ) {
                // Grip handle — same affordance as the portrait bottom sheet.
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
                    orientationPolicy = orientationPolicy,
                    orientationLockRotation = orientationLockRotation,
                    orientationEnabled = orientationEnabled,
                    currentDeviceRotation = currentDeviceRotation,
                    onOrientationPick = onOrientationPick,
                    showTitle = true,
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
                        .clickable(role = Role.Button) { onDismiss() }
                        .padding(vertical = SettingsSheetTokens.ctaPaddingVCompact),
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
    orientationPolicy: String = "FollowDevice",
    orientationLockRotation: Int = 0,
    orientationEnabled: Boolean = true,
    currentDeviceRotation: Int? = null,
    onOrientationPick: (String, Int) -> Unit = { _, _ -> },
    showTitle: Boolean = false,
    showSummary: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var namingVisible by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RovaPreset?>(null) }
    val customNames = presets.filter { !it.isBuiltIn }
    val bodyScroll = rememberScrollState()
    Box(modifier) {
        Column(modifier = Modifier.verticalScroll(bodyScroll)) {
        if (showTitle) {
            Text(
                stringResource(R.string.settings_sheet_title),
                style = RovaTokens.sheetCta,
                color = SettingsSheetTokens.ctaText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(SettingsSheetTokens.modeTabsBottomMargin))
        }
        ModeTabs(currentTopology = currentMode, enabled = editable, onPick = onModePick)
        Spacer(Modifier.height(SettingsSheetTokens.modeTabsBottomMargin))

        PresetGroups(
            presets = presets,
            activePresetId = activePresetId,
            enabled = editable,
            onApply = onApplyPreset,
            onRequestSave = { namingVisible = true },
            onRequestDelete = { pendingDelete = it },
        )

        if (showSummary) {
            val activeName = presets.firstOrNull { it.id == activePresetId }?.name
            Spacer(Modifier.height(SettingsSheetTokens.summaryTopGap))
            SettingsSummary(
                presetName = activeName,
                durationSeconds = durationSeconds,
                loopCount = loopCount,
                intervalMinutes = intervalMinutes,
                quality = quality,
            )
            Spacer(Modifier.height(SettingsSheetTokens.summaryBottomGap))
        } else {
            Spacer(Modifier.height(SettingsSheetTokens.modeTabsBottomMargin))
        }

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
            value = recordRepeatsCompactValue(loopCount),
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
        SheetRowDivider()
        SheetSectionLabel(stringResource(R.string.settings_sheet_orientation))
        Spacer(Modifier.height(SettingsSheetTokens.sectionLabelGap))
        OrientationRow(
            policy = orientationPolicy,
            lockRotation = orientationLockRotation,
            enabled = orientationEnabled,
            currentDeviceRotation = currentDeviceRotation,
            onPick = onOrientationPick,
        )

        if (onResetSnoozes != null) {
            SheetRowDivider()
            ResetSnoozesRow(count = snoozedCount, onClick = onResetSnoozes)
        }
        }
        ScrollFadeBottom(
            visible = bodyScroll.canScrollForward,
            fill = SettingsSheetTokens.sheetFill,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
        )
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
        PresetDeleteDialog(
            target = target,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                onDeletePreset(target)
            },
        )
    }
}

/**
 * Confirm dialog for deleting a custom preset. Extracted from [SettingsContent]
 * (PR-ε floating panel, 2026-06-12) so [FloatingSettingsPanel] reuses the exact
 * same dialog; behavior unchanged.
 */
@Composable
internal fun PresetDeleteDialog(
    target: RovaPreset,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preset_delete_title)) },
        text = { Text(stringResource(R.string.preset_delete_body, target.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.preset_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

/* ── Pieces ───────────────────────────────────────────────────────────── */

// PR-ε floating panel (2026-06-12) — the row-level composables below are
// `internal` (not `private`) so FloatingSettingsPanel.kt can reuse the exact
// same rows with the exact same state plumbing. Visibility-only change; the
// sheet's behavior is untouched.
@Composable
internal fun SheetSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = RovaTokens.sheetSectionLabel,
        color = SettingsSheetTokens.sectionLabelColor,
    )
}

@Composable
internal fun SheetRowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(SettingsSheetTokens.rowDivider),
    )
}

/**
 * Decorative bottom scroll cue — a short transparent→[fill] gradient that
 * appears only while there's more content below ([visible] = canScrollForward),
 * so it never lies. Static (alpha toggles, no animation → not gated by
 * checkA11yAnimationGated); no pointerInput, so it does not intercept the scroll
 * drag underneath. (preset-ui-polish spec §3.)
 */
@Composable
internal fun ScrollFadeBottom(visible: Boolean, fill: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .alpha(if (visible) 1f else 0f)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, fill))),
    )
}

@Composable
internal fun ModeTabs(
    currentTopology: String,
    enabled: Boolean,
    onPick: (String) -> Unit,
    // PR-ε panel refinement (owner 2026-06-12 #8): the floating panel hides
    // the per-mode caption to keep the square card free of helper copy; the
    // Adaptive sheet keeps it (default).
    showCaption: Boolean = true,
) {
    // Active tab paints the liquid-glass accent gradient (royal-violet in the
    // Eclipse theme), matching the record-home ModeCycleChip. White-on-gradient
    // is the documented record-chrome contrast exception (ADR-0020).
    val palette = LocalGlassEnvironment.current.palette
    val activeBrush = remember(palette) { Brush.linearGradient(listOf(palette.accent, palette.accent2)) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SettingsSheetTokens.modeTabsRadius))
                .background(SettingsSheetTokens.modeTabsTrackFill)
                .padding(SettingsSheetTokens.modeTabsPadding),
            horizontalArrangement = Arrangement.spacedBy(SettingsSheetTokens.modeTabsGap),
        ) {
            CaptureMode.visible().forEach { mode ->
                val isActive = CaptureMode.forTopology(currentTopology) == mode
                val label = stringResource(mode.labelRes)
                val tabShape = RoundedCornerShape(SettingsSheetTokens.modeTabRadius)
                val tabModifier = Modifier
                    .weight(1f)
                    .clip(tabShape)
                    // ADR-0020 AA-by-default — selected/disabled state must be
                    // programmatically determinable, matching OrientationRow.
                    .semantics {
                        contentDescription = label
                        selected = isActive
                        role = Role.Tab
                        if (!enabled) disabled()
                    }
                    .let {
                        if (isActive) {
                            it.shadow(1.dp, tabShape).background(activeBrush)
                        } else {
                            it
                        }
                    }
                    .let { if (enabled && !isActive) it.focusHighlight(tabShape).clickable { onPick(mode.topology) } else it }
                    .padding(
                        horizontal = SettingsSheetTokens.modeTabPaddingH,
                        vertical = SettingsSheetTokens.modeTabPaddingV,
                    )
                val textColor = when {
                    isActive -> Color.White
                    !enabled -> SettingsSheetTokens.modeTabDisabledText
                    else -> SettingsSheetTokens.modeTabIdleText
                }
                Box(modifier = tabModifier, contentAlignment = Alignment.Center) {
                    Text(label, style = RovaTokens.sheetModeTab, color = textColor)
                }
            }
        }
        if (showCaption) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(CaptureMode.forTopology(currentTopology).captionRes),
                style = RovaTokens.sheetRowLabel,
                color = SettingsSheetTokens.rowLabelText,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Spec 2026-06-11 §4 — quiet by design; orientation is a setting, not a
 * capture strategy. Inert under DualShot (ADR-0029 §4 — DualShot owns its
 * rotation). Active fill is a neutral alpha rather than the accent gradient,
 * so it reads as a preference control, not a mode selector.
 */
@Composable
internal fun OrientationRow(
    policy: String,
    lockRotation: Int,
    enabled: Boolean,
    currentDeviceRotation: Int?,
    onPick: (String, Int) -> Unit,
) {
    val activeFill = Color.White.copy(alpha = 0.12f)
    val options = listOf(
        Triple(
            stringResource(R.string.orientation_follow_device),
            "FollowDevice",
            -1,
        ),
        Triple(
            stringResource(R.string.orientation_lock_portrait),
            "Lock",
            0,
        ),
        Triple(
            stringResource(R.string.orientation_lock_landscape),
            "Lock",
            lockRotationForLandscapePick(currentDeviceRotation),
        ),
    )
    val trackShape = RoundedCornerShape(SettingsSheetTokens.modeTabsRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(trackShape)
            .background(SettingsSheetTokens.modeTabsTrackFill)
            .padding(SettingsSheetTokens.modeTabsPadding)
            .then(if (!enabled) Modifier.alpha(0.4f) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(SettingsSheetTokens.modeTabsGap),
    ) {
        options.forEach { (label, optPolicy, optLock) ->
            val isActive = when {
                optPolicy == "FollowDevice" -> policy == "FollowDevice"
                optLock in listOf(0, 2) -> policy == "Lock" && lockRotation in listOf(0, 2)
                else -> policy == "Lock" && lockRotation in listOf(1, 3)
            }
            val tabShape = RoundedCornerShape(SettingsSheetTokens.modeTabRadius)
            val tabModifier = Modifier
                .weight(1f)
                .clip(tabShape)
                .let { if (isActive) it.background(activeFill) else it }
                .let {
                    if (enabled && !isActive) {
                        it.focusHighlight(tabShape).clickable(
                            onClickLabel = label,
                        ) { onPick(optPolicy, optLock) }
                    } else it
                }
                .padding(
                    horizontal = SettingsSheetTokens.modeTabPaddingH,
                    vertical = SettingsSheetTokens.modeTabPaddingV,
                )
                .semantics {
                    contentDescription = label
                    selected = isActive
                    role = Role.Tab
                    if (!enabled) disabled()
                }
            val textColor = when {
                isActive -> Color.White
                !enabled -> SettingsSheetTokens.modeTabDisabledText
                else -> SettingsSheetTokens.modeTabIdleText
            }
            Box(modifier = tabModifier, contentAlignment = Alignment.Center) {
                Text(label, style = RovaTokens.sheetModeTab, color = textColor)
            }
        }
    }
}

@Composable
internal fun StepperRow(
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
            .then(if (enabled) Modifier.focusHighlight(shape).clickable(role = Role.Button) { onClick() } else Modifier)
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
internal fun QualityRow(quality: String, enabled: Boolean, onPick: (String) -> Unit) {
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

// `internal` (not private): FloatingSettingsPanel reuses the chip inside its
// Quality disclosure row (PR-ε refinement #2) — same plumbing as QualityRow.
@Composable
internal fun QualityChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(SettingsSheetTokens.chipRadius)
    val palette = LocalGlassEnvironment.current.palette
    val selectedBrush = remember(palette) { Brush.linearGradient(listOf(palette.accent, palette.accent2)) }
    val stroke = if (selected) Color.Transparent else SettingsSheetTokens.chipOffStroke
    val textColor = if (selected) Color.White else SettingsSheetTokens.chipOffText
    // The param is named `selected`, which collides with the `semantics { }`
    // receiver's `selected` property: on the RHS the receiver property (write-only)
    // would win, and on the LHS the bare name resolves to the val param. Alias the
    // RHS read and qualify the LHS with `this.` to target the receiver property.
    val isSelected = selected
    Box(
        modifier = Modifier
            .clip(shape)
            .then(if (selected) Modifier.background(selectedBrush) else Modifier.background(Color.Transparent))
            .border(1.dp, stroke, shape)
            .then(if (enabled) Modifier.focusHighlight(shape).clickable(role = Role.Button) { onClick() } else Modifier)
            // Single-select quality chip: announce on/off state to TalkBack so it is not
            // read as a plain Button (review round: checkA11yClickableHasRole gate landing
            // — SC 4.1.2 Value; sibling ModeTabs/OrientationRow expose `selected` too).
            .semantics { this.selected = isSelected }
            .alpha(if (enabled) 1f else 0.5f)
            .padding(
                horizontal = SettingsSheetTokens.chipPaddingH,
                vertical = SettingsSheetTokens.chipPaddingV,
            ),
    ) {
        Text(label, style = RovaTokens.sheetChip, color = textColor)
    }
}

/* ── Resolved-config summary (PR-β5b) ────────────────────────────────── */

/**
 * One-line echo of the currently-resolved config, e.g. "**Standard** · 30 s
 * clips, every 2 min, ×20, FHD". The leading preset name (when the live config
 * matches a saved preset) is brighter; the value tail reuses the English value
 * vocabulary the steppers already render (gate-exempt formatters). Built as an
 * [androidx.compose.ui.text.AnnotatedString] so it is not a hardcoded `Text("`
 * literal (ADR-0022 §No Hardcoded UI Strings — the segment templates are all
 * localized resources).
 */
@Composable
private fun SettingsSummary(
    presetName: String?,
    durationSeconds: Int,
    loopCount: Int,
    intervalMinutes: Int,
    quality: String,
) {
    val clips = stringResource(R.string.settings_summary_clips, recordClipValue(durationSeconds))
    val every = if (intervalMinutes > 0) {
        stringResource(R.string.settings_summary_every, recordWaitValue(intervalMinutes))
    } else {
        null
    }
    val repeats = if (loopCount < 0) {
        stringResource(R.string.settings_summary_continuous)
    } else {
        stringResource(R.string.settings_summary_repeats, loopCount)
    }
    val tail = listOfNotNull(clips, every, repeats, quality).joinToString("  ·  ")
    val text = buildAnnotatedString {
        if (presetName != null) {
            withStyle(SpanStyle(color = SettingsSheetTokens.summaryStrong, fontWeight = FontWeight.Medium)) {
                append(presetName)
            }
            append("  ·  ")
        }
        withStyle(SpanStyle(color = SettingsSheetTokens.summaryText)) { append(tail) }
    }
    Text(text, style = RovaTokens.sheetRowLabel, modifier = Modifier.fillMaxWidth())
}

/* ── Presets (ADR-0026) ───────────────────────────────────────────────── */

/**
 * Presets, split into a "Built-in" group and a "My presets" group (the latter
 * shown only when the user has saved customs), each a wrapping [FlowRow] of
 * chips. One tap applies the whole config bundle via [onApply]. Built-ins are
 * read-only; customs can be deleted via long-press OR via the "Edit" toggle on
 * the "My presets" header, which reveals an inline × on each custom chip (the
 * non-gesture delete affordance — WCAG SC 2.5.1). The "+ Save" chip appears only
 * when the live config matches no preset and the sheet is editable. Disabled
 * while a session is active (`enabled = editable`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PresetGroups(
    presets: List<RovaPreset>,
    activePresetId: String?,
    enabled: Boolean,
    onApply: (RovaPreset) -> Unit,
    onRequestSave: () -> Unit,
    onRequestDelete: (RovaPreset) -> Unit,
) {
    val builtIns = presets.filter { it.isBuiltIn }
    val customs = presets.filter { !it.isBuiltIn }
    var editMode by remember { mutableStateOf(false) }
    // Auto-exit edit mode once the last custom is gone, so a re-grown list can't
    // reappear already in delete state. Done in an effect (not a composition-time
    // state write) per codex review 019eb252.
    LaunchedEffect(customs.isEmpty()) {
        if (customs.isEmpty()) editMode = false
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SettingsSheetTokens.sectionLabelGap),
    ) {
        SheetSectionLabel(stringResource(R.string.settings_sheet_section_builtin))
        PresetTileGrid(
            builtIns.map { preset ->
                @Composable {
                    PresetTile(
                        preset = preset,
                        selected = preset.id == activePresetId,
                        enabled = enabled,
                        onClick = { onApply(preset) },
                        onLongClick = null,
                    )
                }
            },
        )

        if (customs.isNotEmpty()) {
            Spacer(Modifier.height(SettingsSheetTokens.sectionLabelGap))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetSectionLabel(stringResource(R.string.settings_sheet_section_my_presets))
                Spacer(Modifier.weight(1f))
                if (enabled) {
                    val editShape = RoundedCornerShape(SettingsSheetTokens.chipRadius)
                    val editLabel = if (editMode) {
                        stringResource(R.string.settings_edit_sheet_done)
                    } else {
                        stringResource(R.string.settings_presets_edit)
                    }
                    Text(
                        editLabel,
                        style = RovaTokens.sheetSectionLabel,
                        color = SettingsSheetTokens.chipOffText,
                        modifier = Modifier
                            .clip(editShape)
                            .focusHighlight(editShape)
                            .clickable(role = Role.Button) { editMode = !editMode }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            val customCells: List<@Composable () -> Unit> =
                customs.map { preset ->
                    @Composable {
                        PresetTile(
                            preset = preset,
                            selected = preset.id == activePresetId,
                            enabled = enabled,
                            onClick = { onApply(preset) },
                            // Long-press deletes user customs (built-ins are read-only).
                            onLongClick = { onRequestDelete(preset) },
                            deletable = editMode,
                            onDelete = { onRequestDelete(preset) },
                        )
                    }
                } + if (activePresetId == null && enabled) {
                    listOf<@Composable () -> Unit>({ NewPresetTile(onClick = onRequestSave) })
                } else {
                    emptyList()
                }
            PresetTileGrid(customCells)
        } else if (activePresetId == null && enabled) {
            // No customs yet, but the live config is unsaved → offer the New tile.
            PresetTileGrid(listOf<@Composable () -> Unit>({ NewPresetTile(onClick = onRequestSave) }))
        }
    }
}

/**
 * Equal-width 2-column tile grid. Chunks [cells] into rows of two, each cell
 * `weight(1f)` so columns are identical width regardless of name length; a
 * trailing odd cell pairs with a Spacer. Plain Column/Row (NOT LazyVerticalGrid)
 * so it nests safely inside the panel's existing verticalScroll.
 */
@Composable
private fun PresetTileGrid(cells: List<@Composable () -> Unit>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SettingsSheetTokens.tileGap),
    ) {
        cells.chunked(2).forEach { rowCells ->
            Row(horizontalArrangement = Arrangement.spacedBy(SettingsSheetTokens.tileGap)) {
                rowCells.forEach { cell ->
                    Box(Modifier.weight(1f)) { cell() }
                }
                if (rowCells.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Uniform preset tile — fixed min-height, name (1 line, ellipsis) over a quiet
 * [presetTileSummary]. Selected = faint accent wash + gradient ring + a check
 * badge (selection by more than colour, WCAG 1.4.1). Custom tiles support
 * long-press-to-delete ([onLongClick] + TalkBack label) and, in Edit mode, an
 * inline × ([deletable]/[onDelete]). a11y carried over 1:1 from the old chip:
 * Role.Button, `selected` semantics + spoken [presetSpokenDescription], 48dp
 * min target, disabled state.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetTile(
    preset: RovaPreset,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    deletable: Boolean = false,
    onDelete: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(SettingsSheetTokens.tileRadius)
    val palette = LocalGlassEnvironment.current.palette
    val ringBrush = remember(palette) { Brush.linearGradient(listOf(palette.accent, palette.accent2)) }
    val cd = presetSpokenDescription(preset)
    val deleteLabel = stringResource(R.string.preset_chip_delete_action)
    val summary = presetTileSummary(preset.duration, preset.loopCount, preset.resolution)
    val nameColor = if (selected) SettingsSheetTokens.chipOnText else SettingsSheetTokens.chipOffText
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsSheetTokens.tileMinHeight)
            .clip(shape)
            .background(
                if (selected) palette.accent.copy(alpha = SettingsSheetTokens.tileSelFillAlpha)
                else SettingsSheetTokens.tileFill,
            )
            .then(
                if (selected) Modifier.border(1.5.dp, ringBrush, shape)
                else Modifier.border(1.dp, SettingsSheetTokens.tileStroke, shape),
            )
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
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (selected) {
                // Selection conveyed by more than colour (WCAG 1.4.1) — a leading
                // check that stays visible even in Edit mode, when the TopEnd slot
                // holds the delete ×, so the two cues never collide. codex a11y
                // review 019ec1b3.
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(14.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    preset.name,
                    style = RovaTokens.sheetChip,
                    color = nameColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // reserve room for the TopEnd delete × only when it's shown
                    modifier = Modifier.padding(end = if (deletable) 22.dp else 0.dp),
                )
                Text(
                    summary,
                    style = RovaTokens.tileSummary,
                    color = SettingsSheetTokens.summaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (deletable && onDelete != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .focusHighlight(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = deleteLabel) { onDelete() }
                    // SC 2.5.8 — ≥24dp touch target (parent supplies the action label)
                    .size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = SettingsSheetTokens.chipOnText,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * The "+ Save" affordance as a uniform tile — same footprint as [PresetTile],
 * solid (not dashed) stroke to avoid a custom PathEffect. Shown only when the
 * live config matches no preset; tapping opens the naming dialog (ADR-0026,
 * behavior unchanged).
 */
@Composable
private fun NewPresetTile(onClick: () -> Unit) {
    val shape = RoundedCornerShape(SettingsSheetTokens.tileRadius)
    val cd = stringResource(R.string.preset_save_chip_cd)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsSheetTokens.tileMinHeight)
            .clip(shape)
            .border(1.dp, SettingsSheetTokens.tileStroke, shape)
            .focusHighlight(shape)
            .clickable(role = Role.Button) { onClick() }
            .semantics { contentDescription = cd }
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = SettingsSheetTokens.chipOffText,
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(R.string.preset_save_chip),
                style = RovaTokens.sheetChip,
                color = SettingsSheetTokens.chipOffText,
            )
        }
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
internal fun PresetNameDialog(
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
internal fun ResetSnoozesRow(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusHighlight(RectangleShape)
            .clickable(role = Role.Button) { onClick() }
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
