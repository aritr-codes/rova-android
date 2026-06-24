package com.aritr.rova.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsets as LayoutWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History as HistoryIcon
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aritr.rova.R
import com.aritr.rova.service.RovaRecordingService
import com.aritr.rova.ui.theme.ChromeScale
import com.aritr.rova.ui.theme.DialogActionColors
import com.aritr.rova.ui.theme.GlassRole
import com.aritr.rova.ui.theme.MergeMotion
import com.aritr.rova.ui.theme.GlassSurface
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.RovaGlyphs
import com.aritr.rova.ui.theme.LocalGlassEnvironment
import com.aritr.rova.ui.theme.RecordChromeTokens
import com.aritr.rova.ui.theme.RovaGlyph
import com.aritr.rova.ui.theme.RovaIcons
import com.aritr.rova.ui.theme.RovaTokens
import com.aritr.rova.ui.theme.SemanticIconSpec
import com.aritr.rova.ui.components.RecordHudFormatters
import com.aritr.rova.ui.components.ProcessingGlyph
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.components.RecordHudState
import com.aritr.rova.ui.components.focusHighlight
import com.aritr.rova.ui.components.rememberReduceMotion
import com.aritr.rova.ui.screens.chrome.ChromeOrientation
import com.aritr.rova.ui.screens.chrome.DeviceLandscape
import com.aritr.rova.ui.screens.chrome.railOrder
import com.aritr.rova.ui.screens.chrome.SlotAnchor
import com.aritr.rova.ui.screens.chrome.SlotPlacement
import kotlin.math.roundToInt
import com.aritr.rova.ui.screens.chrome.uprightFadeAlpha
import com.aritr.rova.ui.text.UiText
import com.aritr.rova.ui.text.resolve

// Phase 2 — record chrome consumes the mockup token set (RecordChromeTokens,
// docs/UI_DESIGN_TOKENS.md §2.13). Only values with no token stay local:
// the 48 dp a11y touch box (an interaction metric, not a mockup pixel).
private val ControlBtnTouchSize = 48.dp           // a11y touch target; the glass circle is centered inside
private val StatusPillShape = RoundedCornerShape(RecordChromeTokens.statusPillRadius)
private val PillShape = RoundedCornerShape(RecordChromeTokens.loopPillRadius)
private val SettingsCardShape = RoundedCornerShape(RecordChromeTokens.settingsCardRadiusPill)

/**
 * PR-ε (spec §3) — in-place counter-rotation wrapper. The OUTER Box is the
 * stable layout/interaction container (clickable/semantics belong on it or on
 * an ancestor — modifiers BEFORE a graphicsLayer are not transformed); only the
 * INNER visual child rotates. graphicsLayer is draw-phase-only for layout, so
 * siblings measure against the unrotated bounds — square containers are
 * rotation-invariant (research §3).
 *
 * The child measures UNBOUNDED and centers in the slot: content wider than the
 * slot (e.g. the LOCKED cell's "Landscape" value in a 48dp square) keeps its
 * natural size and draws overflow instead of truncating — the accepted
 * transient-AABB-overflow treatment (research §3); layout siblings still see
 * only the slot bounds.
 */
@Composable
internal fun SpinningBox(
    degrees: () -> Float,
    modifier: Modifier = Modifier,
    fadeOutWhenRotated: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .wrapContentSize(align = Alignment.Center, unbounded = true)
                .graphicsLayer {
                    // FU-4 — draw-phase read of the spin provider; NEVER hoist
                    // `val deg = degrees()` above this block (that reintroduces a
                    // composition read and defeats the optimization).
                    val deg = degrees()
                    rotationZ = deg
                    // spec §5 — labels that exceed their slot fade out instead of
                    // permanently overlapping neighboring chrome at ±90/180.
                    if (fadeOutWhenRotated) alpha = uprightFadeAlpha(deg)
                },
        ) { content() }
    }
}

/**
 * The top-of-viewfinder overlay: a status pill that reads the current mode, plus,
 * in active states, a loop pill ("N/M loops done"). Placed by the caller (RecordScreen)
 * at the top of the camera Box, inset from the status-bar inset. In R1 the active-state
 * text reuses today's elapsed/countdown values; the full restyle of those is R2.
 */
@Composable
fun RecordTopOverlay(
    hudState: RecordHudState,
    statusText: String,            // e.g. "Ready to record" / "Recording" / "On break"
    statusDetail: String?,         // e.g. "0:18 left" / "next in 0:42" / null when idle
    currentLoop: Int,              // for the loop pill in active states
    totalLoops: Int,
    modifier: Modifier = Modifier,
    spinDegrees: () -> Float = { 0f },
) {
    // R2: RecordTopOverlay is now Idle-only (RecordScreen.kt gate); the loop pill
    // (Recording/Waiting) block was removed — unreachable since T9.
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(RecordChromeTokens.topOverlayGap)) {
        // PR-ε (spec §3/§6): the WHOLE status pill (glass + dot + text) spins as
        // one unit in place — info pills stay readable rotated, so no fade; the
        // transposed AABB overflowing into the viewfinder is accepted by design.
        SpinningBox(degrees = spinDegrees) {
            GlassSurface(role = GlassRole.RecordChrome, shape = StatusPillShape) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = RecordChromeTokens.statusPillPaddingH,
                        vertical = RecordChromeTokens.statusPillPaddingV,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RecordChromeTokens.pillContentGap),
                ) {
                    StatusDot(hudState)
                    Text(statusText, style = RovaTokens.statusMain, color = RecordChromeTokens.statusMainText)
                    if (statusDetail != null) {
                        Text(
                            stringResource(R.string.record_status_detail_prefix, statusDetail),
                            style = RovaTokens.statusTime,
                            color = RecordChromeTokens.statusTimeText,
                        )
                    }
                }
            }
        }
    }
}

// R2: RecordTopOverlay is Idle-only (RecordScreen.kt:595 gate); the Recording branch
// of the original when-expression is unreachable. Simplified to the idle/white dot.
@Composable
private fun StatusDot(hudState: RecordHudState) {
    Box(
        Modifier
            .size(RecordChromeTokens.dotSize)
            .clip(CircleShape)
            .background(RecordChromeTokens.dotIdle),
    )
}

// RecordFabState (now {Idle,Recording,Waiting,Processing,Disabled}) + the pure
// state/visual derivation moved to RecordFabVisualSpec.kt (UI Phase 2 PR-2 —
// board-3 FAB lifecycle). Callers use RecordFabVisualSpec.stateFor(...).

/**
 * Floating flash + flip controls, top-right of the viewfinder (mockups/new_uiux/01-record-home.html
 * .cam-controls). Replaces the flash/flip IconButtons in the old app-bar Row. Disabled (greyed)
 * while [enabled] is false (i.e. during an active session — same as today).
 */
@Composable
fun RecordCameraControls(
    flashMode: Int,
    onCycleFlash: () -> Unit,
    onFlip: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    flipEnabled: Boolean = enabled,
    // B6 — true when the FRONT lens is currently bound. Swaps the flip glyph +
    // contentDescription so the affordance names the lens the tap will switch TO.
    isFrontCamera: Boolean = false,
    // PR-β — landscape lays the two controls in a Row (a vertical stack in the
    // top-right would reach down toward the center-right record FAB). Same leaves.
    orientation: ChromeOrientation = ChromeOrientation.PORTRAIT,
    // PR-ε (spec §5) — counter-rotation angle for the button glyphs only; the
    // 48dp circular containers (touch targets) stay stable.
    spinDegrees: () -> Float = { 0f },
) {
    val flashRole = if (enabled) IconRole.Secondary else IconRole.Disabled
    val flipRole = if (flipEnabled) IconRole.Secondary else IconRole.Disabled
    val flash = @Composable { CamFlashButton(flashMode, onCycleFlash, enabled, flashRole, spinDegrees) }
    val flip = @Composable { CamFlipButton(onFlip, flipEnabled, isFrontCamera, flipRole, spinDegrees) }
    if (orientation == ChromeOrientation.LANDSCAPE) {
        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(RecordChromeTokens.camControlGap)) {
            flash(); flip()
        }
    } else {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(RecordChromeTokens.camControlGap)) {
            flash(); flip()
        }
    }
}

@Composable
private fun CamFlashButton(flashMode: Int, onCycleFlash: () -> Unit, enabled: Boolean, role: IconRole, spinDegrees: () -> Float = { 0f }) {
    GlassCircleButton(onClick = onCycleFlash, enabled = enabled) {
        val isOff = flashMode != RovaRecordingService.FLASH_MODE_ON &&
            flashMode != RovaRecordingService.FLASH_MODE_AUTO
        val palette = LocalGlassEnvironment.current.palette
        val contentTint = when { // semanticicon-opt-out: flash-ON is a hardware-state indicator, not a theme/status color
            flashMode == RovaRecordingService.FLASH_MODE_ON && enabled -> Color.Yellow
            // 5b-5 (owner): OFF/AUTO bolt carries the theme accent (parity with FlipCam's accent);
            // disabled falls through to the role-grey tint. palette.accent is theme-derived (gate-safe).
            enabled -> palette.accent
            else -> SemanticIconSpec.tint(palette, role)
        }
        // PR-ε (spec §5): bolt + OFF slash spin as ONE unit so the slash stays
        // diagonal relative to the glyph, not the screen.
        SpinningBox(degrees = spinDegrees) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    RovaGlyphs.FlashBolt,
                    contentDescription = stringResource(R.string.record_flash_cd),
                    tint = contentTint,
                    modifier = Modifier.size(15.dp),
                )
                if (isOff) {
                    // OFF state — diagonal slash across the bolt (mockup shows
                    // only the bolt; the slash is the app's tri-state affordance).
                    Box(
                        Modifier
                            .size(width = 20.dp, height = 1.5.dp)
                            .rotate(-45f)
                            .background(contentTint),
                    )
                }
            }
        }
    }
}

@Composable
private fun CamFlipButton(onFlip: () -> Unit, flipEnabled: Boolean, isFrontCamera: Boolean, role: IconRole, spinDegrees: () -> Float = { 0f }) {
    // Phase 6.1b smoke-fix #6 — flip-camera gated SEPARATELY from [enabled] so
    // P+L mode can disable JUST this button while flash stays usable. P+L is
    // rear-only by design (DualShot needs a single full-FOV sensor frame,
    // software-cropped to portrait AND landscape; the front camera path is not
    // productionised and entry-level Samsung devices like the A17 don't support
    // concurrent rear+front camera streams either).
    GlassCircleButton(onClick = onFlip, enabled = flipEnabled) {
        // B6 — name the lens the tap switches TO (front active → "rear"
        // affordance, and vice-versa). Accurate CD is an ADR-0020 (WCAG)
        // requirement; strings are localized resources (checkNoHardcodedUiStrings).
        // 5b-5: the flip affordance is the rotation-arrows FlipCam glyph (duotone), state-independent
        // — the active lens is conveyed by [flipCd], not by the glyph. (ADR-0031; board `flip_cam`.)
        val flipCd = stringResource(
            if (isFrontCamera) R.string.record_switch_to_rear_cd
            else R.string.record_switch_to_front_cd,
        )
        SpinningBox(degrees = spinDegrees) {
            SemanticIcon(
                glyph = RovaIcons.FlipCam,
                contentDescription = flipCd,
                role = role,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun GlassCircleButton(onClick: () -> Unit, enabled: Boolean, content: @Composable () -> Unit) {
    // The IconButton carries the 48 dp touch target; the glass circle is the
    // smaller RecordChromeTokens.camControlSize visual centered inside it. Sizing
    // the IconButton itself to camControlSize would clamp the hit box to ~30 dp
    // (the incoming fixed constraint defeats IconButton's minimumInteractiveComponentSize)
    // — a regression vs the pre-R1 48 dp flash/flip IconButtons.
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(ControlBtnTouchSize)) {
        GlassSurface(
            role = GlassRole.RecordChrome,
            modifier = Modifier.size(RecordChromeTokens.camControlSize),
            shape = CircleShape,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
        }
    }
}

/**
 * The idle-state settings strip (mockups/new_uiux/01-record-home.html .settings-wrap +
 * .settings-card). A display-only row of cells — Clip / Repeats / Wait / Quality / Mode
 * (Mode is read-only "Portrait" for v1.0.0) — over a "swipe to edit" hint, with a chevron.
 * The whole card is one tap target AND has a swipe-up gesture; both call [onOpenSheet],
 * which opens SessionSettingsSheet.
 */
@Composable
fun RecordSettingsCard(
    durationSeconds: Int,
    loopCount: Int,
    intervalSeconds: Int,
    quality: String,
    mode: String,
    onOpenSheet: () -> Unit,
    onCycleMode: () -> Unit,
    sense: DeviceLandscape? = null,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    orientationPolicy: String = "FollowDevice",
    orientationLockRotation: Int = 0,
    spinDegrees: () -> Float = { 0f },
) {
    Column(
        modifier = modifier
            .then(if (sense == null) Modifier.fillMaxWidth() else Modifier)
            .alpha(if (dimmed) 0.75f else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RecordChromeTokens.settingsWrapGap),
    ) {
        // PR-ε refinement (owner 2026-06-12 #4): the swipe-bar + "SWIPE TO EDIT"
        // hint above the card is REMOVED — the floating panel's spring slide-up
        // open animation is the edit affordance now; the tap/swipe gesture
        // surface on the card itself is unchanged.
        GlassSurface(
            role = GlassRole.RecordChrome,
            modifier = if (sense == null) Modifier.fillMaxWidth() else Modifier,
            shape = SettingsCardShape,
        ) {
            // Shared interaction surface: tap (or swipe) opens the settings sheet.
            // Same gesture contract in both orientations (acceptance — interaction flow).
            val interaction = Modifier
                .heightIn(min = 48.dp)              // a11y minimum touch target
                .then(
                    if (dimmed) {
                        Modifier
                    } else {
                        Modifier
                            .clickable(role = Role.Button) { onOpenSheet() }
                            .pointerInput(Unit) {
                                detectVerticalDragGestures { _, dragAmount ->
                                    if (dragAmount < -8f) onOpenSheet()
                                }
                            }
                    },
                )
                .padding(
                    horizontal = RecordChromeTokens.settingsCardPaddingH,
                    vertical = RecordChromeTokens.settingsCardPaddingV,
                )
            val showLocked = orientationPolicy == "Lock" && !CaptureMode.isAccented(mode)
            val lockedValueStr = if (orientationLockRotation in listOf(1, 3)) {
                stringResource(R.string.record_locked_landscape)
            } else {
                stringResource(R.string.record_locked_portrait)
            }
            if (sense == null) {
                // PORTRAIT — horizontal pill: Clip · Repeats · Wait · Quality · Mode [· Locked].
                Row(
                    modifier = Modifier.fillMaxWidth().then(interaction),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsCell(stringResource(R.string.record_cell_clip), recordClipValue(durationSeconds), Modifier.weight(1f), readOnly = false, spinDegrees = spinDegrees)
                    CellSep()
                    SettingsCell(stringResource(R.string.record_cell_repeats), recordRepeatsCompactValue(loopCount), Modifier.weight(1f), readOnly = false, spinDegrees = spinDegrees)
                    CellSep()
                    SettingsCell(stringResource(R.string.record_cell_wait), recordWaitValue(intervalSeconds), Modifier.weight(1f), readOnly = false, spinDegrees = spinDegrees)
                    CellSep()
                    SettingsCell(stringResource(R.string.record_cell_quality), quality, Modifier.weight(1f), readOnly = false, spinDegrees = spinDegrees)
                    ModeCycleChip(
                        mode = mode,
                        onCycleMode = onCycleMode,
                        onLongPress = onOpenSheet,
                        enabled = !dimmed,
                        modifier = Modifier.weight(1f),
                        spinDegrees = spinDegrees,
                    )
                    if (showLocked) {
                        CellSep()
                        SettingsCell(
                            stringResource(R.string.record_cell_locked),
                            lockedValueStr,
                            Modifier.weight(1f),
                            readOnly = true,
                            spinDegrees = spinDegrees,
                        )
                    }
                    // PR-ε (owner smoke 2026-06-12, finding #3): the expand arrow
                    // spins with the cells — a stable up-arrow reads wrong in
                    // landscape grip.
                    SpinningBox(degrees = spinDegrees, modifier = Modifier.padding(start = 8.dp).size(13.dp)) {
                        Icon(
                            RovaGlyphs.ChevronUp,
                            contentDescription = stringResource(R.string.record_edit_session_settings_cd),
                            tint = RecordChromeTokens.settingsArrow,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            } else {
                // LANDSCAPE — the SAME pill rotated to a vertical column on the cluster
                // edge: identical 5 cells in rotation-mapped order, same SettingsCell /
                // ModeCycleChip widgets, no weights (vertical). All 5 cells incl. Wait.
                // COMPACT density (rotate-spec §11 D1): slimmer type + gap so the column
                // doesn't dominate the rail — owner NO-GO 2026-06-11 on full density.
                // PR-ε note: this branch is Adaptive-fallback-only (sw600dp+ — compact
                // phones lock the window and never reach it); the 44dp-baseline
                // (ChromeScale-scaled, spec §4 amended 2026-06-13) square cell slots
                // make the rail taller than the original compact design — acceptable
                // on tablet heights, re-judge if a device hits it.
                val baseCells = listOf<@Composable () -> Unit>(
                    { SettingsCell(stringResource(R.string.record_cell_clip), recordClipValue(durationSeconds), Modifier, readOnly = false, compact = true, spinDegrees = spinDegrees) },
                    { SettingsCell(stringResource(R.string.record_cell_repeats), recordRepeatsCompactValue(loopCount), Modifier, readOnly = false, compact = true, spinDegrees = spinDegrees) },
                    { SettingsCell(stringResource(R.string.record_cell_wait), recordWaitValue(intervalSeconds), Modifier, readOnly = false, compact = true, spinDegrees = spinDegrees) },
                    { SettingsCell(stringResource(R.string.record_cell_quality), quality, Modifier, readOnly = false, compact = true, spinDegrees = spinDegrees) },
                    { ModeCycleChip(mode = mode, onCycleMode = onCycleMode, onLongPress = onOpenSheet, enabled = !dimmed, compact = true, spinDegrees = spinDegrees) },
                )
                val cells = if (showLocked) {
                    baseCells + listOf<@Composable () -> Unit>({
                        SettingsCell(stringResource(R.string.record_cell_locked), lockedValueStr, Modifier, readOnly = true, compact = true, spinDegrees = spinDegrees)
                    })
                } else {
                    baseCells
                }
                Column(
                    modifier = interaction,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(RecordChromeTokens.landscapeCellGap),
                ) {
                    railOrder(cells, sense).forEach { it() }
                }
            }
        }
    }
}

/**
 * Spec 2026-06-11 §5 Variant A (owner-ratified): accent iff mode != Auto —
 * accent = non-standard capture contract, not decoration.
 *
 * Slice B — Mode tap-cycle chip. Replaces the read-only Mode `SettingsCell`
 * in [RecordSettingsCard]. Tap advances the mode one step via [onCycleMode];
 * long-press opens the settings sheet via [onLongPress] (gesture redundancy +
 * discoverability fallback for the inline cycle). Disabled while [enabled] is
 * false (= card-dimmed during an active session — the existing card behaviour).
 *
 * Visual (accented, mode != Auto): solid `accent → accent2` gradient background,
 * white text and glyph. White-on-bright-accent is the one explicit owner-approved
 * WCAG exception (ADR-0020), scoped to this decorative selected state.
 *
 * Visual (quiet, mode == Auto): no gradient — same background/text treatment as
 * [SettingsCell] (transparent fill within card, [RecordChromeTokens.cellValueText]
 * / [RecordChromeTokens.cellKeyText] colors) so it blends as a plain cell.
 *
 * The chip absorbs tap events within its bounds so taps do NOT bubble to the
 * outer card's `clickable { onOpenSheet() }`.
 *
 * The cycle order lives in [CaptureMode.cycleNext] (CaptureModes.kt) —
 * RecordViewModel.cycleMode() reads the current topology, calls the helper,
 * and writes via the existing setTopology path.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ModeCycleChip(
    mode: String,
    onCycleMode: () -> Unit,
    onLongPress: () -> Unit,
    enabled: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    spinDegrees: () -> Float = { 0f },
) {
    val accented = CaptureMode.isAccented(mode)
    val palette = LocalGlassEnvironment.current.palette
    val selectedBrush = remember(palette) { Brush.linearGradient(listOf(palette.accent, palette.accent2)) }
    val chipShape = RoundedCornerShape(RecordChromeTokens.modeChipCornerRadius)
    val label = stringResource(CaptureMode.forTopology(mode).labelRes)
    Box(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .then(
                if (enabled) {
                    Modifier.combinedClickable(
                        onClick = onCycleMode,
                        onLongClick = onLongPress,
                        role = Role.Button,
                    )
                } else {
                    Modifier
                }
            ),
    ) {
        // PR-ε (spec §3): the accent pill (clip + gradient + label) spins AS A
        // UNIT inside the stable clickable container — clipping on the stable
        // container would cut the rotated label (rotated "DualShot" is taller
        // than the chip's hug-height); the surrounding card gives the spun
        // pill its vertical clearance. The ↻ cycle glyph is REMOVED (owner
        // 2026-06-12 refinement #6 — it overlapped the value text); tap-to-
        // cycle stays discoverable via the chip's accent + long-press sheet.
        //
        // ChromeScale EXEMPT (codex 2026-06-13): unlike SettingsCell, the Mode
        // chip is intrinsically content-hugging (its accent pill must fit
        // "DualShot"), not a fixed square slot — so it carries no scaled
        // `cellSlot`. It already renders shorter than the scaled SettingsCell
        // slots and centres within the row, so the row height (driven by those
        // slots) scales uniformly without it.
        SpinningBox(degrees = spinDegrees, modifier = Modifier.align(Alignment.Center)) {
            Column(
                modifier = Modifier
                    .clip(chipShape)
                    .then(if (accented) Modifier.background(selectedBrush, chipShape) else Modifier)
                    .padding(horizontal = RecordChromeTokens.settingsCellPaddingH, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    style = if (compact) RovaTokens.cellValueCompact else RovaTokens.cellValue,
                    color = if (accented) Color.White else RecordChromeTokens.cellValueText,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Text(
                    stringResource(R.string.record_cell_mode),
                    style = if (compact) RovaTokens.cellKeyCompact else RovaTokens.cellKey,
                    color = if (accented) Color.White.copy(alpha = 0.80f) else RecordChromeTokens.cellKeyText,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Device-anchored scale factor for the config strip + floating panel geometry.
 * Reads the orientation-invariant [android.content.res.Configuration.smallestScreenWidthDp]
 * so the value is stable across rotation (it must NOT resize PR-ε chrome
 * mid-spin). 1.0 on the 411dp reference device; see [ChromeScale]. Shared by
 * [FloatingSettingsPanel] (same package) for the panel-side cap.
 */
@Composable
internal fun rememberChromeScale(): Float {
    val sw = LocalConfiguration.current.smallestScreenWidthDp
    return remember(sw) { ChromeScale.factor(sw.toFloat()) }
}

@Composable
private fun SettingsCell(key: String, value: String, modifier: Modifier, readOnly: Boolean, compact: Boolean = false, spinDegrees: () -> Float = { 0f }) {
    // PR-ε (spec §4, I-style owner-ratified): uniform square slot, content
    // counter-rotates inside it. The OUTER Box keeps the call-site modifier
    // (portrait cells are weight(1f) — a fixed Row constraint that size()
    // cannot override, and the pill must keep filling edge-to-edge); the
    // INNER square (44dp baseline × rememberChromeScale(), spec §4 amended
    // 2026-06-13) is the uniform rotation-invariant spin slot, centered within
    // whatever width the call site assigns.
    Box(modifier, contentAlignment = Alignment.Center) {
        SpinningBox(degrees = spinDegrees, modifier = Modifier.size(RecordChromeTokens.cellSlot * rememberChromeScale())) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    value,
                    style = if (compact) RovaTokens.cellValueCompact else RovaTokens.cellValue,
                    color = if (readOnly) RecordChromeTokens.cellValueReadOnlyText else RecordChromeTokens.cellValueText,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Text(
                    key,
                    style = if (compact) RovaTokens.cellKeyCompact else RovaTokens.cellKey,
                    color = RecordChromeTokens.cellKeyText,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CellSep() {
    Box(Modifier.width(1.dp).height(22.dp).background(RecordChromeTokens.cellDivider))
}

/**
 * Layout metrics RecordScreen needs to clear chrome it can't measure directly.
 *
 * [bottomNavClearance] — bottom padding for content that sits above [RecordBottomNav]
 * (the idle settings card, the active-HUD bands, the merge band): the FAB (⌀ [RecordChromeTokens.fabSize])
 * plus the bar's vertical padding. Pair it with `windowInsetsPadding(navigationBars)` at
 * the call site — that handles the gesture-nav inset separately. One source of truth so
 * the three call sites can't drift; tune here if [RecordBottomNav]'s height changes.
 */
internal object RecordChromeMetrics {
    val bottomNavClearance = 90.dp
    /**
     * Slice B — additional padding above [bottomNavClearance] for the
     * settings card. The dock's gradient has a fully-transparent top
     * zone (35% of dock height ≈ 31 dp); without this lift the
     * settings card's lower edge would overlap the gradient's
     * mid-darkness band, producing a visible alpha-curve seam between
     * two semi-transparent layers. 30 dp lift clears the gradient
     * with an 8 dp buffer.
     */
    val settingsCardLift = 30.dp
}

/**
 * The Record screen's own bottom navigation (mockups/new_uiux/01-record-home.html .bottom-nav):
 * Library (left) · center Start/Stop FAB · Settings (right). There is no app-wide NavigationBar
 * any more — this bar lives only on the Record screen; Library/Settings PUSH those screens.
 *
 * Library/Settings dim + disable while [navItemsLocked] (= isPeriodicActive || isMerging). The FAB
 * shows [RecordFabState.Recording]/[RecordFabState.Waiting] during an active session,
 * [RecordFabState.Processing] during merge, and [RecordFabState.Disabled] on an idle hard-block.
 */
@Composable
fun RecordBottomNav(
    fabState: RecordFabState,
    navItemsLocked: Boolean,
    onLibrary: () -> Unit,
    onSettings: () -> Unit,
    onFabClick: () -> Unit,
    sense: DeviceLandscape? = null,
    modifier: Modifier = Modifier,
    spinDegrees: () -> Float = { 0f },
) {
    // Same three leaves in both orientations (identical widgets/sizes) — only the
    // axis + order change. [railOrder] maps the portrait left→right adjacency to the
    // landscape top→bottom rail so Library/FAB/Settings keep their spatial relation
    // (acceptance — muscle memory).
    val library: @Composable () -> Unit = {
        NavItem(glyph = RovaIcons.Library, label = stringResource(R.string.record_nav_library), enabled = !navItemsLocked, onClick = onLibrary, spinDegrees = spinDegrees)
    }
    val fab: @Composable () -> Unit = { RecordFab(state = fabState, onClick = onFabClick, spinDegrees = spinDegrees) }
    val settings: @Composable () -> Unit = {
        NavItem(glyph = RovaIcons.Settings, label = stringResource(R.string.record_nav_settings), enabled = !navItemsLocked, onClick = onSettings, spinDegrees = spinDegrees)
    }
    if (sense == null) {
        // PORTRAIT — Slice B gradient brush bottom bar. The outer Box paints the brush
        // across its full intrinsic height INCLUDING the nav-bar inset zone the Row
        // consumes via `windowInsetsPadding`, so the gradient dissolves into the
        // OS-transparent gesture-nav region (Slice A — ADR-0011) with no band edge.
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(RecordChromeTokens.bottomNavBrush)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(
                        start = RecordChromeTokens.bottomNavPaddingH,
                        end = RecordChromeTokens.bottomNavPaddingH,
                        top = 14.dp,
                        bottom = RecordChromeTokens.bottomNavPaddingBottom,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                library(); fab(); settings()
            }
        }
    } else {
        // LANDSCAPE — the SAME bar rotated to a vertical rail on the cluster edge:
        // identical leaves, same inter-item spacing token, rotation-mapped order.
        Column(
            modifier = modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(
                    horizontal = RecordChromeTokens.bottomNavPaddingH,
                    vertical = RecordChromeTokens.bottomNavPaddingH,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RecordChromeTokens.bottomNavPaddingH),
        ) {
            railOrder(listOf(library, fab, settings), sense).forEach { it() }
        }
    }
}

@Composable
internal fun NavItem(glyph: RovaGlyph, label: String, enabled: Boolean, onClick: () -> Unit, spinDegrees: () -> Float = { 0f }) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RecordChromeTokens.navItemGap),
        modifier = if (enabled) {
            // SC 2.4.7 (NAV-03/05): visible D-pad/keyboard focus ring.
            Modifier
                .focusHighlight(CircleShape)
                .clickable(onClickLabel = label, role = Role.Button) { onClick() }
        } else {
            Modifier
        },
    ) {
        // 5b-5: Library/Settings sit in the SAME glass circle as the cam-controls (GlassCircleButton)
        // so the nav reads as chrome buttons, not bare glyphs, beside the accent FAB. CircleShape is
        // rotation-invariant — the glyph still spins inside via SpinningBox.
        GlassSurface(
            role = GlassRole.RecordChrome,
            modifier = Modifier.size(RecordChromeTokens.navIconBoxSize),
            shape = CircleShape,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SpinningBox(degrees = spinDegrees) {
                    SemanticIcon(
                        glyph = glyph,
                        contentDescription = label,
                        role = if (enabled) IconRole.Default else IconRole.Disabled,
                        modifier = Modifier.size(RecordChromeTokens.navIconGlyphSize * rememberChromeScale()),
                    )
                }
            }
        }
        // PR-ε refinement (owner 2026-06-12 #5): no visible text label in EITHER
        // orientation — the portrait label vanished in landscape (it faded out
        // when spun), so for consistency it's gone everywhere. [label] still
        // names the item for a11y (Icon contentDescription + onClickLabel).
    }
}

@Composable
internal fun RecordFab(state: RecordFabState, onClick: () -> Unit, spinDegrees: () -> Float = { 0f }) {
    val visual = RecordFabVisualSpec.visualFor(state)
    val palette = LocalGlassEnvironment.current.palette
    // Container brush per board-3 FB lifecycle. AccentDisc deepens the accent→accent2
    // gradient to the AA-safe variant via the same resolver as the premium dialog CTA
    // (owner 2026-06-19: full 4.5:1, no WCAG exception); the OnAccent glyph tint pairs
    // with it. RedDisc is the fixed recording-red gradient; Ghost = translucent accent.
    val containerBrush = when (visual.container) {
        FabContainer.AccentDisc -> {
            val cta = remember(palette.accent, palette.accent2) {
                DialogActionColors.resolve(palette.accent.toFabRgb(), palette.accent2.toFabRgb())
            }
            Brush.linearGradient(listOf(cta.start.toFabColor(), cta.end.toFabColor()))
        }
        FabContainer.RedDisc -> Brush.linearGradient(
            listOf(RecordChromeTokens.fabRecordingDiscStart, RecordChromeTokens.fabRecordingDiscEnd),
        )
        FabContainer.Ghost -> SolidColor(palette.accent.copy(alpha = RecordChromeTokens.fabGhostFillAlpha))
    }
    val isGhost = visual.container == FabContainer.Ghost
    val semanticsLabel = when (visual.label) {
        FabLabel.Start -> stringResource(R.string.record_fab_start_cd)
        FabLabel.Stop -> stringResource(R.string.record_fab_stop_cd)
        FabLabel.Waiting -> stringResource(R.string.record_fab_waiting_cd)
        FabLabel.Processing -> stringResource(R.string.record_fab_processing_cd)
        FabLabel.Unavailable -> stringResource(R.string.record_fab_start_unavailable_cd)
    }
    val glyphSize = RecordChromeTokens.fabGlyphSize * rememberChromeScale()
    Box(
        modifier = Modifier
            .size(RecordChromeTokens.fabSize)
            .clip(CircleShape)
            .background(containerBrush)
            // Ghost states carry only the board 1dp edge; disc states are gradient-only (no border).
            .then(if (isGhost) Modifier.border(1.dp, palette.edge, CircleShape) else Modifier)
            .then(if (visual.enabled) Modifier.clickable { onClick() } else Modifier)
            // SC 4.1.3 (NAV-07): the label tracks the FAB state (Start / Stop / Waiting /
            // Merging); a polite live region announces the transition even when focus is
            // elsewhere on the HUD. SC 4.1.2: inert states (Processing = NonCancellable
            // merge, Disabled = idle hard-block) expose disabled so TalkBack doesn't
            // announce an actionable button.
            .semantics {
                contentDescription = semanticsLabel
                liveRegion = LiveRegionMode.Polite
                role = Role.Button
                if (!visual.enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        // PR-ε (spec §5): only the inner mark spins inside the stable circular surface
        // (container/clickable/semantics untouched). proc_arc self-animates via
        // RecordFabProcessing (reduced-motion → static proc_dots).
        when (visual.glyph) {
            // rec_disc — the Record mark, AA-paired on the accent disc.
            FabGlyph.RecDisc -> SpinningBox(degrees = spinDegrees) {
                SemanticIcon(
                    glyph = RovaIcons.Record, contentDescription = null,
                    role = IconRole.OnAccent, modifier = Modifier.size(glyphSize),
                )
            }
            // rec_morph — white rounded-square stop mark on the red disc.
            FabGlyph.RecMorph -> SpinningBox(degrees = spinDegrees) {
                Box(
                    Modifier
                        .size(RecordChromeTokens.fabRecMorphSize)
                        .clip(RoundedCornerShape(RecordChromeTokens.fabRecMorphRadius))
                        .background(RecordChromeTokens.fabRecMorphFill),
                )
            }
            // rec_ring — resting ring + accent core, dimmed (Disabled).
            FabGlyph.RecRing -> SemanticIcon(
                glyph = RovaIcons.RecordRing, contentDescription = null,
                role = IconRole.Disabled, modifier = Modifier.size(glyphSize),
            )
            // waiting — accent hourglass (Waiting / scheduled).
            FabGlyph.Waiting -> SemanticIcon(
                glyph = RovaIcons.Waiting, contentDescription = null,
                role = IconRole.Accent, modifier = Modifier.size(glyphSize),
            )
            // proc_arc — accent spinner (Processing).
            FabGlyph.ProcArc -> RecordFabProcessing(size = glyphSize)
        }
    }
}

/** Color → board RGB (0..255) for [DialogActionColors]. */
private fun Color.toFabRgb(): IntArray =
    intArrayOf((red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())

private fun IntArray.toFabColor(): Color = Color(this[0], this[1], this[2])

/**
 * Processing FAB mark — board `proc_arc` accent arc spinning one revolution per
 * [MergeMotion.SPIN_PERIOD_MS]. Mirrors [ProcessingGlyph]'s reduced-motion discipline
 * (WCAG 2.2 SC 2.3.3 / 2.2.2, ADR-0020): when the OS toggle is on, render the static
 * `proc_dots` fallback and never build the infinite transition. The same-file
 * `rememberReduceMotion()` read satisfies `checkA11yAnimationGated`.
 */
@Composable
private fun RecordFabProcessing(size: androidx.compose.ui.unit.Dp) {
    val reduceMotion = rememberReduceMotion()
    if (reduceMotion) {
        SemanticIcon(
            glyph = RovaIcons.ProcessingDots, contentDescription = null,
            role = IconRole.Accent, modifier = Modifier.size(size),
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "fabProcSpin")
    val fraction by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = MergeMotion.SPIN_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "fabProcFraction",
    )
    SemanticIcon(
        glyph = RovaIcons.Processing, contentDescription = null, role = IconRole.Accent,
        modifier = Modifier
            .size(size)
            .graphicsLayer { rotationZ = MergeMotion.angle(fraction, reduceMotion = false) },
    )
}

/**
 * Low-key recovery nudge on the Record idle screen (a chip near the status pill). Recovery
 * *cards* live on the Library (the replan / ADR 0005); this is just the landing-screen ping.
 * Replaces the visual of the old RecoveryEchoBanner; RecordScreen still computes [count] from
 * RovaApp.recoveryReport via RecoveryViewSource.eligibleSessionCount.
 */
@Composable
fun RecordRecoveryChip(count: Int, onReview: () -> Unit, modifier: Modifier = Modifier, spinDegrees: () -> Float = { 0f }) {
    // WCAG 2.2 AA SC 1.3.1 / 4.1.2 (ADR-0020, REC-19): one button node with a
    // single spoken name — the decorative history glyph (CD=null) and the label
    // otherwise read as separate fragments.
    val chipDescription = pluralStringResource(R.plurals.record_recovery_chip_cd, count, count)
    val chipLabel = pluralStringResource(R.plurals.record_recovery_chip, count, count)
    val reviewLabel = stringResource(R.string.record_recovery_review)
    // PR-ε (spec §3): clickable + semantics stay on the STABLE outer Box (hit-
    // testing follows graphicsLayer — a rotated clickable would rotate the touch
    // target); the whole visual chip (glass + glyph + label) spins inside it.
    Box(
        modifier = modifier
            .clickable(onClickLabel = reviewLabel, role = Role.Button) { onReview() }
            .semantics(mergeDescendants = true) { contentDescription = chipDescription },
    ) {
        SpinningBox(degrees = spinDegrees) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.40f),
                contentColor = Color.White,
            ) {
                Row(
                    Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    SemanticIcon(Icons.Default.HistoryIcon, contentDescription = null, role = IconRole.Secondary, modifier = Modifier.size(14.dp))
                    Text(chipLabel, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                }
            }
        }
    }
}

// ── R2 active-HUD helpers (Phase A). Composables that consume these land in Task 8.

/**
 * R2 — loop-pill text. Returns `null` when there's only one clip or zero clips (single-clip
 * and zero-clip sessions hide the pill entirely; the status-pill alone carries the state).
 * Indefinite sessions (`loopTotal < 0`) render the index without a total. The index is
 * clamped on both ends.
 */
internal fun loopPillContent(loopIndex: Int, loopTotal: Int): UiText? = when {
    loopTotal == 1 || loopTotal == 0 -> null   // single-clip or zero-clip — hide the pill
    // StrArgs (not Plural): the copy never pluralizes "loops", so "1 loops done"
    // must stay byte-identical — a <plurals> would grammar-correct it to "1 loop done".
    loopTotal < 0  -> UiText.StrArgs(
        R.string.record_hud_loops_done_indefinite,
        listOf(loopIndex.coerceAtLeast(0)),
    )
    else           -> UiText.StrArgs(
        R.string.record_hud_loops_done,
        listOf(loopIndex.coerceIn(0, loopTotal), loopTotal),
    )
}

/**
 * R2/PR2 follow-up — segmented loop-progress model for [LoopSegmentBar] (mockup
 * `.m-seg`). Pure / JVM-testable. Mirrors [loopPillContent]'s hide gate: single-
 * clip, zero-clip, and indefinite sessions show no bar. Small finite totals get
 * discrete dots; large totals collapse to a continuous fraction so the bar never
 * renders dozens of slivers.
 */
internal sealed interface LoopSegments {
    data class Discrete(val total: Int, val filled: Int) : LoopSegments
    data class Continuous(val fraction: Float) : LoopSegments
}

internal fun loopSegments(loopIndex: Int, loopTotal: Int, maxDiscrete: Int = 8): LoopSegments? = when {
    loopTotal < 0 -> null                 // indefinite — no fixed total to segment
    loopTotal <= 1 -> null                // single/zero clip — hide (matches loopPillContent)
    loopTotal <= maxDiscrete -> LoopSegments.Discrete(loopTotal, loopIndex.coerceIn(0, loopTotal))
    else -> LoopSegments.Continuous((loopIndex.toFloat() / loopTotal).coerceIn(0f, 1f))
}

internal enum class StatusDotColor { RECORDING, WAITING, MERGING }

internal data class StatusPillContent(
    val dot: StatusDotColor,
    val main: UiText,
    val time: UiText,
)

/**
 * R2 — status-pill content per HUD state. Pure. `clipSecondsLeft` / `waitSecondsLeft`
 * come from RecordScreen's existing `produceState` timers (R1 preserve list); the
 * helper takes them as ints rather than reading off `RecordHudState`, which holds no
 * countdown fields. Idle is a caller bug — the active HUD must not be mounted at idle.
 */
internal fun hudStatusPillContent(
    state: RecordHudState,
    clipSecondsLeft: Int,
    waitSecondsLeft: Int,
): StatusPillContent = when (state) {
    RecordHudState.Recording -> StatusPillContent(
        dot = StatusDotColor.RECORDING,
        main = UiText.Str(R.string.record_hud_status_recording),
        time = UiText.StrArgs(
            R.string.record_hud_time_left,
            listOf(RecordHudFormatters.formatMmSs(clipSecondsLeft.toLong())),
        ),
    )
    // Bug B — startup grace before the first clip. Reuses the amber/slate
    // WAITING dot (no new color token); the caption is a static phrase, not a
    // countdown, because nothing is timing down during the pre-record grace.
    RecordHudState.Starting -> StatusPillContent(
        dot = StatusDotColor.WAITING,
        main = UiText.Str(R.string.record_hud_status_starting),
        time = UiText.Str(R.string.record_hud_starting_caption),
    )
    RecordHudState.Waiting -> StatusPillContent(
        dot = StatusDotColor.WAITING,
        main = UiText.Str(R.string.record_hud_status_on_break),
        time = UiText.StrArgs(
            R.string.record_hud_time_next_in,
            listOf(RecordHudFormatters.formatMmSs(waitSecondsLeft.toLong())),
        ),
    )
    is RecordHudState.Merging -> StatusPillContent(
        dot = StatusDotColor.MERGING,
        main = UiText.Str(R.string.record_hud_status_merging),
        time = UiText.StrArgs(
            R.string.record_hud_time_percent,
            listOf((state.progress * 100).toInt().coerceIn(0, 100)),
        ),
    )
    RecordHudState.Idle ->
        error("hudStatusPillContent called with Idle — caller bug; gate on hudState != Idle")
}

/**
 * WCAG 2.2 AA SC 4.1.3 (ADR-0020, REC-22): TalkBack announcement for the
 * active HUD, published into a polite live region on [RecordActiveHud].
 *
 * Deliberately omits the per-second clip/wait countdown — a live region that
 * re-announced every tick would chant over the user. The string changes only
 * on meaningful boundaries (state transition, loop roll, merge segment roll),
 * which is the right granularity for a status message. Pure / JVM-testable.
 */
internal fun hudActiveAnnouncement(
    state: RecordHudState,
    loopIndex: Int,
    loopTotal: Int,
): UiText? = when (state) {
    // Anti-chant is unchanged: the token still carries only the boundary-level
    // status (no per-second countdown), so the resolved live-region string
    // changes only on state/loop/merge-segment transitions. The sole "nothing
    // to announce" case is Idle — formerly "" — which is now null (resolved back
    // to "" at the call site, byte-identical).
    RecordHudState.Recording -> announceForState(
        bare = R.string.record_hud_announce_recording,
        loop = R.string.record_hud_announce_recording_loop,
        loopOf = R.string.record_hud_announce_recording_loop_of,
        loopIndex = loopIndex,
        loopTotal = loopTotal,
    )
    // Bug B — boundary-level announcement for the startup grace. A single
    // static phrase (no loop suffix) — there is no loop position yet because
    // the first clip has not started.
    RecordHudState.Starting -> UiText.Str(R.string.record_hud_announce_starting)
    RecordHudState.Waiting -> announceForState(
        bare = R.string.record_hud_announce_on_break,
        loop = R.string.record_hud_announce_on_break_loop,
        loopOf = R.string.record_hud_announce_on_break_loop_of,
        loopIndex = loopIndex,
        loopTotal = loopTotal,
    )
    is RecordHudState.Merging ->
        // Forwarded from a separate, char-for-char-tested helper that owns its own
        // clamp/branch logic; it now returns the real UiText token (B3 task 2b),
        // resolved at the same `.resolve()` edge as the other announcement tokens.
        RecordHudFormatters.formatMergeAnnouncement(state.currentSegment, state.totalSegments)
    RecordHudState.Idle -> null
}

/**
 * Selects the bare / "Loop N" / "Loop N of M" announcement token, mirroring the
 * original `loopPhrase` branch exactly (single/zero-clip → bare, indefinite →
 * loop-only with a clamped index, finite → loop-of-total with a clamped index).
 */
private fun announceForState(
    @androidx.annotation.StringRes bare: Int,
    @androidx.annotation.StringRes loop: Int,
    @androidx.annotation.StringRes loopOf: Int,
    loopIndex: Int,
    loopTotal: Int,
): UiText = when {
    loopTotal == 1 || loopTotal == 0 -> UiText.Str(bare)
    loopTotal < 0 -> UiText.StrArgs(loop, listOf(loopIndex.coerceAtLeast(0)))
    else -> UiText.StrArgs(loopOf, listOf(loopIndex.coerceIn(0, loopTotal), loopTotal))
}

// ── R2 active-HUD composables (Task 8). Consume the Phase-A helpers above. ──

@Composable
private fun StatusDot(dot: StatusDotColor, modifier: Modifier = Modifier) {
    val color = when (dot) {
        StatusDotColor.RECORDING -> RecordChromeTokens.dotRecording
        StatusDotColor.WAITING   -> RecordChromeTokens.dotBreak   // slate #94A3B8 — mockup .dot-break
        // MERGING never reaches StatusDot — StatusPill routes it to ProcessingGlyph
        // (Icon P2 Track A). The arm stays for exhaustiveness; tripping it is a caller bug.
        StatusDotColor.MERGING   -> error("StatusDot is not rendered for MERGING; StatusPill uses ProcessingGlyph")
    }
    if (dot == StatusDotColor.RECORDING) {
        // mockup `.dot-recording` — blink 1.8s ease-in-out + a red glow.
        // Compose has no box-shadow; the glow is a radial-gradient halo drawn
        // behind the dot, pulsing on the same transition as the dot alpha.
        // WCAG 2.2 AA SC 2.3.3 / 2.2.2 (ADR-0020, REC-14): hold the dot static
        // and fully visible when the OS reduced-motion toggle is on. Hook stays
        // unconditional; only the emitted value is gated.
        val reduceMotion = rememberReduceMotion()
        val transition = rememberInfiniteTransition(label = "recordingDot")
        val animatedPulse by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "recordingDotPulse",
        )
        val pulse = if (reduceMotion) 1f else animatedPulse
        Box(
            modifier
                .size(20.dp)                       // 8 dp dot + room for the ~8 dp halo
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                color.copy(alpha = 0.55f * pulse),
                                color.copy(alpha = 0f),
                            ),
                            radius = size.minDimension / 2f,
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = pulse)),
            )
        }
    } else {
        Box(
            modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
private fun LoopPill(loopIndex: Int, loopTotal: Int, modifier: Modifier = Modifier, spinDegrees: () -> Float = { 0f }) {
    // loopPillContent is the tested (untouched) hide-gate: null ⇒ single-clip /
    // zero-clip ⇒ no pill. The mockup splits the body into a numeral + a caption,
    // so the numeral is re-derived here with the same clamp loopPillContent uses.
    loopPillContent(loopIndex, loopTotal) ?: return
    val numeral = if (loopTotal < 0) {
        "${loopIndex.coerceAtLeast(0)}"
    } else {
        "${loopIndex.coerceIn(0, loopTotal)}/$loopTotal"
    }
    // PR-ε (spec §3/§6): the whole pill (surface + numeral + caption) spins as one
    // unit, AFTER the hide-gate so a hidden pill still emits no node (an empty
    // SpinningBox would consume the HUD Column's spacedBy gap). No fade — info
    // pills must stay readable rotated.
    SpinningBox(degrees = spinDegrees, modifier = modifier) {
        Surface(
            shape = PillShape,
            color = RecordChromeTokens.glassFill,
            contentColor = Color.White,
            border = BorderStroke(1.dp, RecordChromeTokens.glassStroke),
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = RecordChromeTokens.loopPillPaddingH,
                    vertical = RecordChromeTokens.loopPillPaddingV,
                ),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(RecordChromeTokens.loopPillContentGap),
            ) {
                Text(numeral, style = RovaTokens.loopCount, color = RecordChromeTokens.loopCountText)
                Text(stringResource(R.string.record_loops_done_caption), style = RovaTokens.loopUnit, color = RecordChromeTokens.loopUnitText)
            }
        }
    }
}

/**
 * R2/PR2 follow-up — the recording-progress segment bar (mockup `.m-seg`,
 * `rova_design_system_round3.html`: "Progress dots fill left-to-right as clips
 * complete"). A thin ~200 dp bar whose segments fill left→right with the theme
 * `accent → accent2` gradient as loops/clips complete. Reads the live palette via
 * [LocalGlassEnvironment] so it tracks the selected theme (same anchor the
 * selected-mode chip uses). Self-hides for single/zero-clip and indefinite
 * sessions via [loopSegments]'s null gate (mirrors [LoopPill]). No blur effect —
 * the fill is a solid gradient over a 12%-white track, matching `.m-seg i`.
 */
@Composable
private fun LoopSegmentBar(loopIndex: Int, loopTotal: Int, modifier: Modifier = Modifier, spinDegrees: () -> Float = { 0f }) {
    val segments = loopSegments(loopIndex, loopTotal) ?: return
    val palette = LocalGlassEnvironment.current.palette
    val fillBrush = Brush.linearGradient(listOf(palette.accent, palette.accent2))
    val track = Color.White.copy(alpha = 0.12f)   // mockup .m-seg i background
    val segShape = RoundedCornerShape(3.dp)
    val barHeight = 5.dp
    // PR-ε (spec §3, §5): the 200dp bar CANNOT take the info-pill spin — rotated
    // ±90° it would stand ~200dp tall and cross the HUD pills above it, violating
    // the §3 transpose-clearance rule. It fades out when spun instead (§5 over-
    // slot treatment); the timer pill stays the readable landscape-grip element.
    SpinningBox(degrees = spinDegrees, modifier = modifier, fadeOutWhenRotated = true) {
        when (segments) {
            is LoopSegments.Discrete -> Row(
                modifier = Modifier.width(200.dp).height(barHeight),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(segments.total) { i ->
                    val cell = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(segShape)
                    // Two .background overloads (Brush vs Color) keep the cell a single
                    // node — filled segments paint the accent gradient, the rest the track.
                    Box(if (i < segments.filled) cell.background(fillBrush) else cell.background(track))
                }
            }
            is LoopSegments.Continuous -> Box(
                modifier = Modifier.width(200.dp).height(barHeight).clip(segShape).background(track),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(segments.fraction)
                        .clip(segShape)
                        .background(fillBrush),
                )
            }
        }
    }
}

@Composable
private fun StatusPill(content: StatusPillContent, modifier: Modifier = Modifier, spinDegrees: () -> Float = { 0f }) {
    // PR-ε (spec §3/§6): whole pill (surface + dot + timer text) spins as one
    // unit — mid-REC the timer rotates so it reads upright in landscape grip
    // (deliberate divergence from One UI). No fade.
    SpinningBox(degrees = spinDegrees, modifier = modifier) {
        Surface(
            shape = StatusPillShape,
            color = RecordChromeTokens.glassFill,
            contentColor = Color.White,
            border = BorderStroke(1.dp, RecordChromeTokens.glassStroke),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = RecordChromeTokens.statusPillPaddingH, vertical = RecordChromeTokens.statusPillPaddingV),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RecordChromeTokens.pillContentGap),
            ) {
                if (content.dot == StatusDotColor.MERGING) {
                    // Icon P2 Track A — branded animated merge glyph replaces the
                    // static dot for the Merging HUD state (ADR-0031 §6/§8).
                    ProcessingGlyph(size = 18.dp)
                } else {
                    StatusDot(content.dot)
                }
                Text(
                    content.main.resolve(),
                    style = RovaTokens.statusMain,
                    color = RecordChromeTokens.statusMainText,
                )
                Text(
                    content.time.resolve(),
                    style = RovaTokens.statusTime,
                    color = RecordChromeTokens.statusTimeText,
                )
            }
        }
    }
}

/**
 * R2 — top-anchored active-state HUD. Stacks [LoopPill] (when applicable) above [StatusPill].
 * MUST NOT be mounted at [com.aritr.rova.ui.components.RecordHudState.Idle] — the
 * `hudStatusPillContent` helper throws for Idle (caller-bug guard).
 *
 * Layout: vertical Column at the top safe-area, centered, with 8 dp spacing between the
 * loop-pill and the status-pill. Both pills are glass-on-camera (R1 token set).
 */
/**
 * PR-β — maps a pure [SlotPlacement] to a Compose Modifier inside a Box: align +
 * (optional) window inset + edge padding + (optional) width cap, in that order
 * (matches today's `align().windowInsetsPadding().padding()` order, so portrait
 * is byte-identical). [insets] is the region's existing inset (statusBars for
 * top regions, navigationBars for bottom/side regions); pass null for none.
 */
@Composable
fun BoxScope.slotModifier(p: SlotPlacement, insets: LayoutWindowInsets? = null): Modifier {
    val alignment = when (p.anchor) {
        SlotAnchor.TOP_START     -> Alignment.TopStart
        SlotAnchor.TOP_END       -> Alignment.TopEnd
        SlotAnchor.TOP_CENTER    -> Alignment.TopCenter
        SlotAnchor.BOTTOM_CENTER -> Alignment.BottomCenter
        SlotAnchor.CENTER_START  -> Alignment.CenterStart
        SlotAnchor.CENTER_END    -> Alignment.CenterEnd
    }
    var m = Modifier.align(alignment)
    if (insets != null) m = m.windowInsetsPadding(insets)
    m = m.padding(start = p.startDp.dp, top = p.topDp.dp, end = p.endDp.dp, bottom = p.bottomDp.dp)
    if (p.maxWidthDp != null) m = m.widthIn(max = p.maxWidthDp.dp)
    return m
}

@Composable
internal fun RecordActiveHud(
    state: RecordHudState,
    loopIndex: Int,
    loopTotal: Int,
    clipSecondsLeft: Int,
    waitSecondsLeft: Int,
    rotatingNextClip: Boolean = false,
    orientation: ChromeOrientation = ChromeOrientation.PORTRAIT,
    modifier: Modifier = Modifier,
    spinDegrees: () -> Float = { 0f },
) {
    // SC 4.1.3 (REC-22): one polite live region carrying a stable, boundary-
    // only announcement. mergeDescendants + an explicit contentDescription
    // replaces the pills' volatile per-second text so TalkBack speaks the
    // status once per transition instead of chanting the countdown.
    // Resolve UiText? → String?; null (Idle) collapses to "" to stay byte-identical
    // with the prior empty-announcement contract. liveRegion wiring is unchanged.
    val announcement = hudActiveAnnouncement(state, loopIndex, loopTotal)?.resolve() ?: ""
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
            contentDescription = announcement
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // PR-β — landscape places the loop pill beside the status pill (a vertical
        // stack reads badly in the short landscape top band). Same composables; the
        // outer Column keeps the live-region semantics either way (ADR-0020).
        if (orientation == ChromeOrientation.LANDSCAPE) {
            // Adaptive fallback branch (window actually landscape): spinDegrees
            // is always 0f here — args passed for uniformity only.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoopPill(loopIndex = loopIndex, loopTotal = loopTotal, spinDegrees = spinDegrees)
                StatusPill(content = hudStatusPillContent(state, clipSecondsLeft, waitSecondsLeft), spinDegrees = spinDegrees)
            }
            if (rotatingNextClip) {
                Text(
                    text = stringResource(R.string.record_orientation_rotating_next),
                    style = RovaTokens.statusTime,
                    color = RecordChromeTokens.statusTimeText,
                )
            }
            LoopSegmentBar(loopIndex = loopIndex, loopTotal = loopTotal, spinDegrees = spinDegrees)
        } else {
            // PR-ε fix (owner smoke 2026-06-12, finding #2): per-pill spin made
            // the rotated pills' AABBs overlap each other — the Column spaces
            // them by UNROTATED heights, so two ~120dp-wide pills rotated ±90°
            // collided. The HUD stack spins AS ONE GROUP instead: relative
            // arrangement is preserved inside the spun block (pills can never
            // collide with each other) and the block's transposed AABB extends
            // only into empty viewfinder below the top band (spec §3 clearance).
            // Children receive no spin (identity); the segment bar rotates with
            // the group and stays readable instead of fading.
            SpinningBox(degrees = spinDegrees) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LoopPill(loopIndex = loopIndex, loopTotal = loopTotal)
                    StatusPill(content = hudStatusPillContent(state, clipSecondsLeft, waitSecondsLeft))
                    // PR-α (ADR-0029 §Decision 3) — next clip adopts the device's
                    // new orientation at the segment boundary; quiet caption.
                    if (rotatingNextClip) {
                        Text(
                            text = stringResource(R.string.record_orientation_rotating_next),
                            style = RovaTokens.statusTime,
                            color = RecordChromeTokens.statusTimeText,
                        )
                    }
                    // mockup `.m-seg` — self-hides (loopSegments null gate).
                    LoopSegmentBar(loopIndex = loopIndex, loopTotal = loopTotal)
                }
            }
        }
    }
}
