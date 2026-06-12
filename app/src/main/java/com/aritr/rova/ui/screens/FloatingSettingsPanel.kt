package com.aritr.rova.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.data.RovaPreset
import com.aritr.rova.ui.components.focusHighlight
import com.aritr.rova.ui.components.rememberReduceMotion
import com.aritr.rova.ui.theme.RovaTokens
import com.aritr.rova.ui.theme.SettingsSheetTokens

// PR-ε floating panel geometry (plan 2026-06-12 decision 3) — near-square
// centered card, width capped min(parentWidth − 44dp, 320dp), anchored
// upper-center ≈18% from the top of a compact screen so the strip/nav stay
// visible below; total height capped at the screen width so the card's
// footprint is rotation-invariant (a 90°-spun card never exceeds the
// portrait-locked window's width).
private val PanelTopAnchor = 120.dp
private val PanelMaxWidth = 320.dp
private val PanelEdgeClearance = 44.dp

/**
 * PR-ε floating settings panel — the FixedPhysical (compact, <sw600dp)
 * presentation of the combined recording-settings surface. V1 content
 * (full-label stepper rows, no scrim, "Presets" collapsed) in V3 geometry
 * (near-square centered card), owner-ratified 2026-06-12 from
 * `floating_panel_mockup.html`.
 *
 * Surface-class exception (ADR-0029 §B″5 rewrite): this surface IS record
 * chrome, not a window surface. It counter-rotates as ONE unit via
 * [SpinningBox] — the window NEVER unlocks on compact. The Adaptive
 * (sw600dp+) branch keeps the pre-existing [SettingsSheet]
 * bottom-sheet/side-panel presentations verbatim.
 *
 * State plumbing is identical to [SettingsSheet]: edits write through
 * immediately via the same callbacks; ✕ / tap-outside / back dismiss (same
 * save-on-dismiss semantics as the sheet's Save CTA). Rows are the SAME
 * `internal` composables the sheet renders ([ModeTabs], [StepperRow],
 * [QualityRow], [OrientationRow], [PresetGroups], [ResetSnoozesRow]).
 *
 * Open/close fade+scale and the inline presets expansion are gated on
 * [rememberReduceMotion] (ADR-0020 / checkA11yAnimationGated convention).
 */
@Composable
internal fun FloatingSettingsPanel(
    visible: Boolean,
    spinDegrees: Float,
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
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    AnimatedVisibility(
        visible = visible,
        enter = if (reduceMotion) EnterTransition.None else fadeIn() + scaleIn(initialScale = 0.92f),
        exit = if (reduceMotion) ExitTransition.None else fadeOut() + scaleOut(targetScale = 0.92f),
    ) {
        BackHandler(enabled = visible, onBack = onDismiss)
        var namingVisible by remember { mutableStateOf(false) }
        var pendingDelete by remember { mutableStateOf<RovaPreset?>(null) }
        // Full-screen tap-catcher: NO scrim (visual), but it consumes input so
        // the chrome below — which stays VISIBLE under the floating card —
        // can't be hit while the panel is open; an outside tap dismisses.
        BoxWithConstraints(
            modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        ) {
            val panelWidth = (maxWidth - PanelEdgeClearance).coerceAtMost(PanelMaxWidth)
            val panelMaxHeight = minOf(maxWidth, maxHeight - PanelTopAnchor - PanelEdgeClearance)
            SpinningBox(
                degrees = spinDegrees,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = PanelTopAnchor),
            ) {
                val panelShape = remember { RoundedCornerShape(SettingsSheetTokens.sheetCornerRadius) }
                val panelTitle = stringResource(R.string.settings_sheet_title)
                Column(
                    modifier = Modifier
                        .width(panelWidth)
                        .heightIn(max = panelMaxHeight)
                        // A11y (ADR-0020): pane announcement on open — TalkBack
                        // reads the title and moves focus into the panel, whose
                        // under-chrome is semantics-pruned while it's open.
                        .semantics { paneTitle = panelTitle }
                        .clip(panelShape)
                        .background(SettingsSheetTokens.sheetFill)
                        .border(1.dp, SettingsSheetTokens.sheetTopStroke, panelShape)
                        // Consume taps inside the card so they don't fall
                        // through to the dismiss catcher (child controls still
                        // receive their own taps — detectTapGestures only).
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(horizontal = SettingsSheetTokens.sheetPaddingH)
                        .padding(top = 10.dp, bottom = 14.dp),
                ) {
                    PanelHeader(onDismiss = onDismiss)
                    Spacer(Modifier.height(SettingsSheetTokens.modeTabsBottomMargin))
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ModeTabs(currentTopology = currentMode, enabled = editable, onPick = onModePick)
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
                        SheetRowDivider()

                        // "Presets…" collapsed row (plan decision 4) — expands the
                        // same preset chip groups the sheet renders, inline.
                        var presetsExpanded by remember { mutableStateOf(false) }
                        PresetsToggleRow(
                            expanded = presetsExpanded,
                            onToggle = { presetsExpanded = !presetsExpanded },
                        )
                        AnimatedVisibility(
                            visible = presetsExpanded,
                            enter = if (reduceMotion) EnterTransition.None else expandVertically() + fadeIn(),
                            exit = if (reduceMotion) ExitTransition.None else shrinkVertically() + fadeOut(),
                        ) {
                            Column {
                                Spacer(Modifier.height(SettingsSheetTokens.sectionLabelGap))
                                PresetGroups(
                                    presets = presets,
                                    activePresetId = activePresetId,
                                    enabled = editable,
                                    onApply = onApplyPreset,
                                    onRequestSave = { namingVisible = true },
                                    onRequestDelete = { pendingDelete = it },
                                )
                            }
                        }

                        if (onResetSnoozes != null) {
                            SheetRowDivider()
                            ResetSnoozesRow(count = snoozedCount, onClick = onResetSnoozes)
                        }
                    }
                }
            }
        }
        if (namingVisible) {
            PresetNameDialog(
                existingCustoms = presets.filter { !it.isBuiltIn },
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
}

/** Title + ✕ header row; ✕ mirrors the sheet's Save CTA (dismiss = save). */
@Composable
private fun PanelHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.settings_sheet_title),
            style = RovaTokens.sheetCta,
            color = SettingsSheetTokens.ctaText,
            modifier = Modifier.weight(1f),
        )
        val closeCd = stringResource(R.string.settings_panel_close_cd)
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .focusHighlight(CircleShape)
                .clickable(role = Role.Button, onClickLabel = closeCd) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = closeCd,
                tint = SettingsSheetTokens.ctaText,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** "Presets" disclosure row — chevron flips when expanded (static, no animation). */
@Composable
private fun PresetsToggleRow(expanded: Boolean, onToggle: () -> Unit) {
    val label = stringResource(R.string.settings_sheet_section_presets)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusHighlight(RectangleShape)
            .clickable(role = Role.Button, onClickLabel = label) { onToggle() }
            .padding(vertical = SettingsSheetTokens.rowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = RovaTokens.sheetRowLabel,
            color = SettingsSheetTokens.rowLabelText,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = SettingsSheetTokens.rowLabelText,
            modifier = Modifier
                .size(18.dp)
                .rotate(if (expanded) 180f else 0f),
        )
    }
}
