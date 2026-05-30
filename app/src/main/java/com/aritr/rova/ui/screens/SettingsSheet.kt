package com.aritr.rova.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aritr.rova.data.QualityPresets
import com.aritr.rova.ui.components.focusHighlight
import com.aritr.rova.ui.theme.RovaTokens
import com.aritr.rova.ui.theme.SettingsSheetTokens

/**
 * Settings sheet — re-skin of `mockups/new_uiux/02-settings-sheet.html`.
 *
 * A custom bottom-anchored panel (NOT a Material `ModalBottomSheet`): the live
 * camera "peeks" through the translucent top [SettingsSheetTokens.peekHeight]
 * behind a scrim; the opaque panel below holds inline mode tabs, `−`/value/`+`
 * steppers, quality chips, and a Save CTA. Edits write through immediately;
 * "Save", the handle drag-down, and system-back all just dismiss.
 *
 * The caller emits this composable unconditionally and toggles [visible] — the
 * slide animation owns its own mount lifetime. The caller suppresses the record
 * chrome while [visible] so only the camera shows through the peek.
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
    statusText: String,
    flashMode: Int,
    flipEnabled: Boolean,
    onCycleFlash: () -> Unit,
    onFlip: () -> Unit,
    onDurationChange: (Int) -> Unit,
    onLoopCountChange: (Int) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onQualityChange: (String) -> Unit,
    onModePick: (String) -> Unit,
    // Phase 4.1c — "Reset snoozed warnings" affordance. `onResetSnoozes` is
    // null when the persisted set is empty; the row is suppressed entirely
    // in that state so there is no dead-end "Reset (0)" affordance.
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
                controlsEnabled = editable,
                onCycleFlash = onCycleFlash,
                onFlip = onFlip,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(SettingsSheetTokens.peekHeight),
            )
            SettingsPanel(
                durationSeconds = durationSeconds,
                loopCount = loopCount,
                intervalMinutes = intervalMinutes,
                quality = quality,
                currentMode = currentMode,
                editable = editable,
                onDurationChange = onDurationChange,
                onLoopCountChange = onLoopCountChange,
                onIntervalChange = onIntervalChange,
                onQualityChange = onQualityChange,
                onModePick = onModePick,
                snoozedCount = snoozedCount,
                onResetSnoozes = onResetSnoozes,
                onDismiss = onDismiss,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(top = SettingsSheetTokens.peekHeight),
            )
        }
    }
}

/* ── Camera peek ──────────────────────────────────────────────────────── */

@Composable
private fun SettingsPeek(
    statusText: String,
    flashMode: Int,
    flipEnabled: Boolean,
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
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(end = SettingsSheetTokens.camControlsInsetEnd, top = 7.dp),
        )
    }
}

/* ── Sheet panel ──────────────────────────────────────────────────────── */

@Composable
private fun SettingsPanel(
    durationSeconds: Int,
    loopCount: Int,
    intervalMinutes: Int,
    quality: String,
    currentMode: String,
    editable: Boolean,
    onDurationChange: (Int) -> Unit,
    onLoopCountChange: (Int) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onQualityChange: (String) -> Unit,
    onModePick: (String) -> Unit,
    snoozedCount: Int,
    onResetSnoozes: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val panelShape = remember {
        RoundedCornerShape(
            topStart = SettingsSheetTokens.sheetCornerRadius,
            topEnd = SettingsSheetTokens.sheetCornerRadius,
        )
    }
    Column(
        modifier = modifier
            .clip(panelShape)
            .background(SettingsSheetTokens.sheetFill)
            .border(1.dp, SettingsSheetTokens.sheetTopStroke, panelShape)
            // Drag down anywhere on the panel to dismiss (native bottom-sheet
            // feel). Detector lives on the whole Column — not the tiny handle
            // bar — so the gesture target is the full panel, not a 4 dp strip.
            // Vertical drags don't steal taps from the steppers/chips: a
            // `clickable` consumes the tap, the drag detector only engages
            // past touch-slop.
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
        // Handle — visual affordance only; the whole panel is drag-to-dismiss.
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

        SheetSectionLabel("Recording mode")
        Spacer(Modifier.height(SettingsSheetTokens.sectionLabelGap))
        ModeTabs(currentMode = currentMode, enabled = editable, onPick = onModePick)
        Spacer(Modifier.height(SettingsSheetTokens.modeTabsBottomMargin))

        SheetSectionLabel("Settings")
        Spacer(Modifier.height(SettingsSheetTokens.sectionLabelGap))

        StepperRow(
            label = "Clip Duration",
            value = recordClipValue(durationSeconds),
            enabled = editable,
            atMin = RecordSettingBounds.clipAtMin(durationSeconds),
            atMax = RecordSettingBounds.clipAtMax(durationSeconds),
            onStep = { dir -> onDurationChange(RecordSettingBounds.stepClip(durationSeconds, dir)) },
        )
        SheetRowDivider()
        StepperRow(
            label = "Repeats",
            value = recordRepeatsStepperValue(loopCount),
            enabled = editable,
            atMin = RecordSettingBounds.repeatsAtMin(loopCount),
            atMax = RecordSettingBounds.repeatsAtMax(loopCount),
            onStep = { dir -> onLoopCountChange(RecordSettingBounds.stepRepeats(loopCount, dir)) },
        )
        SheetRowDivider()
        StepperRow(
            label = "Wait Between",
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

        // Push the CTA to the bottom of the panel.
        Spacer(Modifier.weight(1f))
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
            Text("Save", style = RovaTokens.sheetCta, color = SettingsSheetTokens.ctaText)
        }
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

private enum class SheetModeTab(val label: String, val value: String) {
    Portrait("Portrait", "Portrait"),
    Landscape("Landscape", "Landscape"),
    PortraitLandscape("P + L", "PortraitLandscape"),
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
                Text(tab.label, style = RovaTokens.sheetModeTab, color = textColor)
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
            "Quality",
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
                "Reset snoozed warnings",
                style = RovaTokens.sheetRowLabel,
                color = SettingsSheetTokens.rowLabelText,
            )
            Text(
                if (count == 1) "1 warning hidden" else "$count warnings hidden",
                style = RovaTokens.sheetRowLabel,
                color = SettingsSheetTokens.rowLabelText.copy(alpha = 0.55f),
                fontSize = 11.sp,
            )
        }
    }
}
