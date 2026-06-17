package com.aritr.rova.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.data.QualityPresets
import com.aritr.rova.data.RovaPreset
import com.aritr.rova.ui.components.focusHighlight
import com.aritr.rova.ui.components.rememberReduceMotion
import com.aritr.rova.ui.theme.RovaTokens
import com.aritr.rova.ui.theme.SettingsSheetTokens

// PR-ε floating panel geometry (owner refinement round 2026-06-12 #1/#3) —
// an EXACT square, horizontally centered, anchored just above the config
// strip (V1 spacing in `floating_panel_mockup.html`). A square footprint is
// rotation-INVARIANT: the card spins as one unit via SpinningBox and a
// square never changes its AABB, so it can never clip a window edge in a
// landscape grip (round-1 rectangle did — Screenshot_20260612_220008).
//
// BASELINE side — multiplied by `rememberChromeScale()` at use (owner refinement
// 2026-06-13): the cap tracks screen size, pinned to this value on the 411dp
// reference device and scaling on narrower phones / wider tablets & foldables.
private val PanelMaxSide = 320.dp
private val PanelEdgeClearance = 44.dp
// Bottom anchor: nav-bar inset + the strip's own clearance stack. The strip
// (PARAMS_CARD slot) sits at navInset + 120dp (CARD_BOTTOM_P — pinned by the
// ChromeSlotPlacement test). The 62dp strip-height term is the PRE-slim value
// (48dp slot + padding); the 2026-06-13 slim trimmed the strip to ~56dp, but
// this term is left at 62 ON PURPOSE so the panel does NOT move on the
// reference device (owner: "panel is perfect") — the consequence is the gap
// above the strip reads ~6dp larger (≈22dp vs the V1 mockup's 16dp), a benign
// touch more breathing room. Revisit only if the owner wants the gap tightened.
private val PanelBottomClearance = 120.dp + 62.dp + 16.dp
// Minimum breathing room above the square (status-bar band + margin) when the
// window is short (split-screen) and the side is height-capped.
private val PanelTopMargin = 56.dp

/**
 * PR-ε floating settings panel — the FixedPhysical (compact, <sw600dp)
 * presentation of the combined recording-settings surface. Owner-refined
 * 2026-06-12 (round 2): exact centered square anchored above the strip;
 * Quality / Orientation / Preset are single-line disclosure ("dropdown
 * style") rows that expand INLINE; no section labels or helper copy.
 *
 * Surface-class exception (ADR-0029 §B″5): this surface IS record chrome,
 * not a window surface. It counter-rotates as ONE unit via [SpinningBox] —
 * the window NEVER unlocks on compact. The Adaptive (sw600dp+) branch keeps
 * the pre-existing [SettingsSheet] presentations verbatim.
 *
 * The expanded selector content stays INSIDE the spun card on purpose: a
 * Material DropdownMenu spawns its own window, which would NOT inherit the
 * SpinningBox graphicsLayer rotation and would pop up sideways in a
 * landscape grip. Inline expansion is the only rotation-safe "dropdown".
 *
 * Open/close = fade catcher + spring slide-up on the card (round3 mockup
 * `springUp` cubic-bezier(.22,1.2,.3,1) ≈ medium-low-stiffness spring with
 * mild overshoot). The slide offset is applied INSIDE the SpinningBox layer
 * via [androidx.compose.animation.AnimatedVisibilityScope.animateEnterExit],
 * so the card always rises "from below" in its READING orientation, not in
 * window coordinates. All motion is gated on [rememberReduceMotion]
 * (ADR-0020 / checkA11yAnimationGated).
 *
 * State plumbing is identical to [SettingsSheet]: edits write through
 * immediately via the same callbacks; ✕ / tap-outside / back dismiss.
 */
@Composable
internal fun FloatingSettingsPanel(
    visible: Boolean,
    spinDegrees: () -> Float,
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
        enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(160)),
        exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(160)),
    ) {
        // FU-3 — the exit animation runs while this content is STILL composed.
        // Keep the back gesture consumed until the transition settles so a
        // back-press can't race the fade-out and pop the nav (enabled = visible
        // alone went false the instant dismissal started). While exiting we
        // consume as a NO-OP; only a press while actually visible re-fires dismiss.
        val exiting = transition.targetState == EnterExitState.PostExit
        val backArmed = transition.currentState == EnterExitState.Visible ||
            transition.targetState == EnterExitState.Visible
        BackHandler(enabled = backArmed) {
            if (!exiting) onDismiss()
        }
        var namingVisible by remember { mutableStateOf(false) }
        var pendingDelete by remember { mutableStateOf<RovaPreset?>(null) }
        // One disclosure row open at a time — keeps the square's scroll area
        // tidy and reads like a single dropdown moving between rows.
        var expandedRow by remember { mutableStateOf<PanelRow?>(null) }
        // Full-screen tap-catcher: NO scrim (visual), but it consumes input so
        // the chrome below — which stays VISIBLE under the floating card —
        // can't be hit while the panel is open; an outside tap dismisses.
        BoxWithConstraints(
            modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        ) {
            // Exact square (#3): same side in every grip. On compact the window
            // is permanently portrait, so maxWidth is the short edge; the side
            // also never exceeds the V3 mockup cap. Additionally capped by the
            // vertical room ABOVE the bottom anchor (codex review): a short
            // window (split-screen / freeform) would otherwise push the square
            // past the top edge — the bottom offset is fixed, so the side must
            // shrink instead.
            // Device-anchored responsive cap: PanelMaxSide × ChromeScale (== 1.0
            // on the 411dp reference device, so the side stays its tuned ~320dp
            // here; narrower phones shrink, tablets/foldables grow). Still bounded
            // by the available width/height above the bottom anchor.
            val chromeScale = rememberChromeScale()
            val panelSide = minOf(
                maxWidth - PanelEdgeClearance,
                maxHeight - PanelBottomClearance - PanelTopMargin,
            ).coerceAtMost(PanelMaxSide * chromeScale)
            SpinningBox(
                degrees = spinDegrees,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = PanelBottomClearance),
            ) {
                val panelShape = remember { RoundedCornerShape(SettingsSheetTokens.sheetCornerRadius) }
                val panelTitle = stringResource(R.string.settings_sheet_title)
                Column(
                    modifier = Modifier
                        .size(panelSide)
                        .then(
                            if (reduceMotion) {
                                Modifier
                            } else {
                                // Spring slide-up INSIDE the spun layer — rises
                                // toward the reading-frame top in every grip.
                                with(this@AnimatedVisibility) {
                                    Modifier.animateEnterExit(
                                        enter = slideInVertically(
                                            animationSpec = spring(
                                                dampingRatio = 0.75f,
                                                stiffness = Spring.StiffnessMediumLow,
                                                visibilityThreshold = IntOffset.VisibilityThreshold,
                                            ),
                                        ) { it / 3 },
                                        exit = slideOutVertically(tween(140)) { it / 4 },
                                    )
                                }
                            },
                        )
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
                    val panelScroll = rememberScrollState()
                    Box(modifier = Modifier.weight(1f, fill = false)) {
                        Column(
                            modifier = Modifier.verticalScroll(panelScroll),
                        ) {
                        // Owner refinement #8 — no mode caption, no section
                        // labels: the rows speak for themselves.
                        ModeTabs(
                            currentTopology = currentMode,
                            enabled = editable,
                            onPick = onModePick,
                            showCaption = false,
                        )
                        Spacer(Modifier.height(SettingsSheetTokens.modeTabsBottomMargin))

                        StepperRow(
                            label = stringResource(R.string.settings_sheet_clip_duration),
                            value = recordClipValue(durationSeconds),
                            enabled = editable,
                            atMin = RecordSettingBounds.clipAtMin(durationSeconds),
                            atMax = RecordSettingBounds.clipAtMax(durationSeconds),
                            onStep = { dir -> onDurationChange(RecordSettingBounds.stepClip(durationSeconds, dir)) },
                            editValue = durationSeconds,
                            onSetValue = { onDurationChange(RecordSettingBounds.clampClip(it)) },
                        )
                        SheetRowDivider()
                        StepperRow(
                            label = stringResource(R.string.settings_sheet_repeats),
                            value = recordRepeatsCompactValue(loopCount),
                            enabled = editable,
                            atMin = RecordSettingBounds.repeatsAtMin(loopCount),
                            atMax = RecordSettingBounds.repeatsAtMax(loopCount),
                            onStep = { dir -> onLoopCountChange(RecordSettingBounds.stepRepeats(loopCount, dir)) },
                            // ∞ (continuous = -1): dialog opens EMPTY so OK can't silently turn ∞→1.
                            editValue = loopCount.takeIf { it != RecordSettingBounds.REPEATS_CONTINUOUS },
                            onSetValue = { onLoopCountChange(RecordSettingBounds.clampRepeats(it)) },
                        )
                        SheetRowDivider()
                        StepperRow(
                            label = stringResource(R.string.settings_sheet_wait_between),
                            value = recordWaitValue(intervalMinutes),
                            enabled = editable,
                            atMin = RecordSettingBounds.waitAtMin(intervalMinutes),
                            atMax = RecordSettingBounds.waitAtMax(intervalMinutes),
                            onStep = { dir -> onIntervalChange(RecordSettingBounds.stepWait(intervalMinutes, dir)) },
                            editValue = intervalMinutes,
                            onSetValue = { onIntervalChange(RecordSettingBounds.clampWait(it)) },
                        )
                        SheetRowDivider()

                        // Owner refinement #2 — Quality / Orientation / Preset as
                        // dropdown-style disclosure rows (V3 mockup), inline-expanding.
                        PanelDisclosureRow(
                            label = stringResource(R.string.settings_sheet_quality),
                            value = QualityPresets.canonicalizeOrDefault(quality),
                            expanded = expandedRow == PanelRow.QUALITY,
                            reduceMotion = reduceMotion,
                            onToggle = { expandedRow = if (expandedRow == PanelRow.QUALITY) null else PanelRow.QUALITY },
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(SettingsSheetTokens.chipGroupGap)) {
                                val current = QualityPresets.canonicalizeOrDefault(quality)
                                QualityPresets.PICKER_ORDER.forEach { option ->
                                    QualityChip(
                                        label = option,
                                        selected = option == current,
                                        enabled = editable,
                                        onClick = { onQualityChange(option) },
                                    )
                                }
                            }
                        }
                        SheetRowDivider()
                        val orientationValue = when {
                            orientationPolicy != "Lock" -> stringResource(R.string.orientation_follow_device)
                            orientationLockRotation in listOf(1, 3) -> stringResource(R.string.orientation_lock_landscape)
                            else -> stringResource(R.string.orientation_lock_portrait)
                        }
                        PanelDisclosureRow(
                            label = stringResource(R.string.settings_sheet_orientation),
                            value = orientationValue,
                            expanded = expandedRow == PanelRow.ORIENTATION,
                            reduceMotion = reduceMotion,
                            onToggle = { expandedRow = if (expandedRow == PanelRow.ORIENTATION) null else PanelRow.ORIENTATION },
                        ) {
                            OrientationRow(
                                policy = orientationPolicy,
                                lockRotation = orientationLockRotation,
                                enabled = orientationEnabled,
                                currentDeviceRotation = currentDeviceRotation,
                                onPick = onOrientationPick,
                            )
                        }
                        SheetRowDivider()
                        val activePresetName = presets.firstOrNull { it.id == activePresetId }?.name
                            ?: stringResource(R.string.settings_panel_preset_none)
                        PanelDisclosureRow(
                            label = stringResource(R.string.settings_sheet_section_presets),
                            value = activePresetName,
                            expanded = expandedRow == PanelRow.PRESET,
                            reduceMotion = reduceMotion,
                            onToggle = { expandedRow = if (expandedRow == PanelRow.PRESET) null else PanelRow.PRESET },
                        ) {
                            PresetGroups(
                                presets = presets,
                                activePresetId = activePresetId,
                                enabled = editable,
                                onApply = onApplyPreset,
                                onRequestSave = { namingVisible = true },
                                onRequestDelete = { pendingDelete = it },
                            )
                        }

                        if (onResetSnoozes != null) {
                            SheetRowDivider()
                            ResetSnoozesRow(count = snoozedCount, onClick = onResetSnoozes)
                        }
                        }
                        ScrollFadeBottom(
                            visible = panelScroll.canScrollForward,
                            fill = SettingsSheetTokens.sheetFill,
                            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                        )
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

/** Which disclosure row is open — at most one at a time. */
private enum class PanelRow { QUALITY, ORIENTATION, PRESET }

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

/**
 * Dropdown-style disclosure row (V3 mockup `label … value ▾`): one line of
 * label + current value + chevron; tapping expands [expandedContent] inline
 * below it. Inline (not a popup) — see the rotation-safety note on
 * [FloatingSettingsPanel]. Expanded/collapsed state is exposed to TalkBack
 * via stateDescription (ADR-0020).
 */
@Composable
private fun PanelDisclosureRow(
    label: String,
    value: String,
    expanded: Boolean,
    reduceMotion: Boolean,
    onToggle: () -> Unit,
    expandedContent: @Composable () -> Unit,
) {
    val stateText = stringResource(
        if (expanded) R.string.settings_panel_row_expanded else R.string.settings_panel_row_collapsed,
    )
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusHighlight(RectangleShape)
                .clickable(role = Role.Button, onClickLabel = label) { onToggle() }
                .semantics { stateDescription = stateText }
                .padding(vertical = SettingsSheetTokens.rowPaddingV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = RovaTokens.sheetRowLabel,
                color = SettingsSheetTokens.rowLabelText,
                modifier = Modifier.weight(1f),
            )
            Text(
                value,
                style = RovaTokens.sheetStepValue,
                color = SettingsSheetTokens.stepValText,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = SettingsSheetTokens.rowLabelText,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduceMotion) EnterTransition.None else expandVertically() + fadeIn(),
            exit = if (reduceMotion) ExitTransition.None else shrinkVertically() + fadeOut(),
        ) {
            Column {
                expandedContent()
                Spacer(Modifier.height(SettingsSheetTokens.sectionLabelGap))
            }
        }
    }
}
