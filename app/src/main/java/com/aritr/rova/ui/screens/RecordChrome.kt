package com.aritr.rova.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aritr.rova.service.RovaRecordingService
import com.aritr.rova.ui.theme.RecordChromeTokens
import com.aritr.rova.ui.theme.RovaTokens
import com.aritr.rova.ui.components.RecordHudFormatters
import com.aritr.rova.ui.components.RecordHudState

// Phase 2 — record chrome consumes the mockup token set (RecordChromeTokens,
// docs/UI_DESIGN_TOKENS.md §2.13). Only values with no token stay local:
// the merging-dot colour (the mockup defines no merging dot) and the 48 dp
// a11y touch box (an interaction metric, not a mockup pixel).
private val MergingDotColor = Color(0xFF60A5FA)   // blue — no mockup token (mockup has idle/recording/break only)
private val ControlBtnTouchSize = 48.dp           // a11y touch target; the glass circle is centered inside
private val StatusPillShape = RoundedCornerShape(RecordChromeTokens.statusPillRadius)
private val PillShape = RoundedCornerShape(RecordChromeTokens.loopPillRadius)
private val SettingsCardShape = RoundedCornerShape(RecordChromeTokens.settingsCardRadius)

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
) {
    // R2: RecordTopOverlay is now Idle-only (RecordScreen.kt gate); the loop pill
    // (Recording/Waiting) block was removed — unreachable since T9.
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(RecordChromeTokens.topOverlayGap)) {
        Row(
            modifier = Modifier
                .clip(StatusPillShape)
                .background(RecordChromeTokens.glassFill)
                .border(1.dp, RecordChromeTokens.glassStroke, StatusPillShape)
                .padding(
                    horizontal = RecordChromeTokens.statusPillPaddingH,
                    vertical = RecordChromeTokens.statusPillPaddingV,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RecordChromeTokens.pillContentGap),
        ) {
            StatusDot(hudState)
            Text(statusText, style = RovaTokens.statusMain, color = RecordChromeTokens.statusMainText)
            if (statusDetail != null) {
                Text("· $statusDetail", style = RovaTokens.statusTime, color = RecordChromeTokens.statusTimeText)
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

/** What the center button in the Record bottom nav shows / does. */
enum class RecordFabState { Start, Stop, Disabled }

/**
 * Pure derivation of the center-FAB state from the HUD state + gating booleans.
 *
 * - Idle + a hard-block active (camera permission denied OR storage insufficient
 *   to start — see [com.aritr.rova.ui.warnings.WarningCenterViewModel] / RecordScreen's
 *   leaf-signal reads) → [Disabled]; the actionable CTA lives in the auto-presented
 *   warning sheet, not the FAB.
 * - Idle, not blocked → [Start].
 * - Recording or Waiting → [Stop] (always — `sessionLocked`/`hardBlockActive` are
 *   irrelevant once a session is running).
 * - Merging → [Disabled] — the merge runs `NonCancellable` (ADR 0006), so a Stop
 *   affordance would be a lie.
 *
 * `sessionLocked` (= isPeriodicActive || isMerging) is passed for symmetry / future
 * use; the FAB is `Stop` (not `Disabled`) during an active session.
 */
fun recordFabState(
    hudState: RecordHudState,
    sessionLocked: Boolean,
    hardBlockActive: Boolean,
): RecordFabState = when (hudState) {
    RecordHudState.Idle -> if (hardBlockActive) RecordFabState.Disabled else RecordFabState.Start
    RecordHudState.Recording, RecordHudState.Waiting -> RecordFabState.Stop
    is RecordHudState.Merging -> RecordFabState.Disabled
}

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
) {
    val tint = if (enabled) Color.White.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.3f)
    val flipTint = if (flipEnabled) Color.White.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.3f)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(RecordChromeTokens.camControlGap)) {
        GlassCircleButton(onClick = onCycleFlash, enabled = enabled) {
            val isOff = flashMode != RovaRecordingService.FLASH_MODE_ON &&
                flashMode != RovaRecordingService.FLASH_MODE_AUTO
            val contentTint = when {
                flashMode == RovaRecordingService.FLASH_MODE_ON && enabled -> Color.Yellow
                else -> tint
            }
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    RecordChromeIcons.flashBolt,
                    contentDescription = "Flash",
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
        // Phase 6.1b smoke-fix #6 — flip-camera gated SEPARATELY from
        // [enabled] so P+L mode can disable JUST this button while
        // flash stays usable. P+L is rear-only by design (DualShot needs
        // a single full-FOV sensor frame, software-cropped to portrait
        // AND landscape; the front camera path is not productionised
        // and entry-level Samsung devices like the A17 don't support
        // concurrent rear+front camera streams either).
        GlassCircleButton(onClick = onFlip, enabled = flipEnabled) {
            Icon(
                RecordChromeIcons.flipCamera,
                contentDescription = "Flip camera",
                tint = flipTint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun GlassCircleButton(onClick: () -> Unit, enabled: Boolean, content: @Composable () -> Unit) {
    // The IconButton carries the 48 dp touch target; the glass circle is the
    // smaller ControlBtnSize visual centered inside it. Sizing the IconButton
    // itself to ControlBtnSize would clamp the hit box to ~30 dp (the incoming
    // fixed constraint defeats IconButton's minimumInteractiveComponentSize) —
    // a regression vs the pre-R1 48 dp flash/flip IconButtons.
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(ControlBtnTouchSize)) {
        Box(
            modifier = Modifier
                .size(RecordChromeTokens.camControlSize)
                .clip(CircleShape)
                .background(RecordChromeTokens.camControlFill)
                .border(1.dp, RecordChromeTokens.camControlStroke, CircleShape),
            contentAlignment = Alignment.Center,
        ) { content() }
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
    intervalMinutes: Int,
    quality: String,
    mode: String,
    onOpenSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(RecordChromeTokens.settingsWrapGap)) {
        // swipe-to-edit hint
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(bottom = 1.dp)) {
            Box(
                Modifier
                    .width(RecordChromeTokens.swipeBarWidth)
                    .height(RecordChromeTokens.swipeBarHeight)
                    .clip(RoundedCornerShape(1.dp))
                    .background(RecordChromeTokens.swipeHint),
            )
            Text("SWIPE TO EDIT", style = RovaTokens.swipeLabel, color = RecordChromeTokens.swipeHint)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)              // a11y minimum touch target
                .clip(SettingsCardShape)
                .background(RecordChromeTokens.settingsCardFill)
                .border(1.dp, RecordChromeTokens.settingsCardStroke, SettingsCardShape)
                .clickable { onOpenSheet() }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount -> if (dragAmount < -8f) onOpenSheet() }
                }
                .padding(
                    horizontal = RecordChromeTokens.settingsCardPaddingH,
                    vertical = RecordChromeTokens.settingsCardPaddingV,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsCell("Clip", recordClipValue(durationSeconds), Modifier.weight(1f), readOnly = false)
            CellSep()
            SettingsCell("Repeats", recordRepeatsValue(loopCount), Modifier.weight(1f), readOnly = false)
            CellSep()
            SettingsCell("Wait", recordWaitValue(intervalMinutes), Modifier.weight(1f), readOnly = false)
            CellSep()
            SettingsCell("Quality", quality, Modifier.weight(1f), readOnly = false)
            CellSep()
            SettingsCell("Mode", mode, Modifier.weight(1f), readOnly = true)
            Icon(
                RecordChromeIcons.chevronUp,
                contentDescription = "Edit session settings",
                tint = RecordChromeTokens.settingsArrow,
                modifier = Modifier.padding(start = 8.dp).size(13.dp),
            )
        }
    }
}

@Composable
private fun SettingsCell(key: String, value: String, modifier: Modifier, readOnly: Boolean) {
    Column(modifier = modifier.padding(horizontal = RecordChromeTokens.settingsCellPaddingH), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = RovaTokens.cellValue,
            color = if (readOnly) RecordChromeTokens.cellValueReadOnlyText else RecordChromeTokens.cellValueText,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            key.uppercase(),
            style = RovaTokens.cellKey,
            color = RecordChromeTokens.cellKeyText,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
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
 * (the idle settings card, the active-HUD bands, the merge band): the FAB (⌀ [FabSize])
 * plus the bar's vertical padding. Pair it with `windowInsetsPadding(navigationBars)` at
 * the call site — that handles the gesture-nav inset separately. One source of truth so
 * the three call sites can't drift; tune here if [RecordBottomNav]'s height changes.
 */
internal object RecordChromeMetrics {
    val bottomNavClearance = 90.dp
}

/**
 * The Record screen's own bottom navigation (mockups/new_uiux/01-record-home.html .bottom-nav):
 * Library (left) · center Start/Stop FAB · Settings (right). There is no app-wide NavigationBar
 * any more — this bar lives only on the Record screen; Library/Settings PUSH those screens.
 *
 * Library/Settings dim + disable while [navItemsLocked] (= isPeriodicActive || isMerging). The FAB
 * is [RecordFabState.Stop] during an active session and [RecordFabState.Disabled] when a hard-block
 * is active (idle) or during merge.
 */
@Composable
fun RecordBottomNav(
    fabState: RecordFabState,
    navItemsLocked: Boolean,
    onLibrary: () -> Unit,
    onSettings: () -> Unit,
    onFabClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(RecordChromeTokens.bottomNavFill)
            .border(width = 1.dp, color = RecordChromeTokens.bottomNavTopStroke, shape = RoundedCornerShape(0.dp))
            .windowInsetsPadding(WindowInsets.navigationBars)   // clear the gesture-nav bar
            .padding(
                start = RecordChromeTokens.bottomNavPaddingH,
                end = RecordChromeTokens.bottomNavPaddingH,
                top = 14.dp,
                bottom = RecordChromeTokens.bottomNavPaddingBottom,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        NavItem(icon = RecordChromeIcons.library, label = "Library", enabled = !navItemsLocked, onClick = onLibrary)
        RecordFab(state = fabState, onClick = onFabClick)
        NavItem(icon = RecordChromeIcons.settings, label = "Settings", enabled = !navItemsLocked, onClick = onSettings)
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RecordChromeTokens.navItemGap),
        modifier = if (enabled) Modifier.clickable { onClick() } else Modifier,
    ) {
        Box(
            modifier = Modifier
                .size(RecordChromeTokens.navIconBoxSize)
                .clip(RoundedCornerShape(RecordChromeTokens.navIconCornerRadius)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (enabled) RecordChromeTokens.navIcon else Color.White.copy(alpha = 0.14f),
                modifier = Modifier.size(RecordChromeTokens.navIconGlyphSize),
            )
        }
        Text(
            label,
            style = RovaTokens.navTxt,
            color = if (enabled) RecordChromeTokens.navText else Color.White.copy(alpha = 0.12f),
        )
    }
}

@Composable
private fun RecordFab(state: RecordFabState, onClick: () -> Unit) {
    val (fill, stroke, semanticsLabel) = when (state) {
        RecordFabState.Start -> Triple(RecordChromeTokens.fabStartFill, RecordChromeTokens.fabStartStroke, "Start recording")
        RecordFabState.Stop -> Triple(RecordChromeTokens.fabStopFill, RecordChromeTokens.fabStopStroke, "Stop recording")
        RecordFabState.Disabled -> Triple(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.08f), "Start recording (unavailable)")
    }
    val enabled = state != RecordFabState.Disabled
    Box(contentAlignment = Alignment.Center) {
        if (state == RecordFabState.Stop) {
            // .btn-stop::after — a ring inset -5 dp (extends outward).
            Box(
                Modifier
                    .size(RecordChromeTokens.fabSize + RecordChromeTokens.fabStopRingInset * 2)
                    .clip(CircleShape)
                    .border(1.dp, RecordChromeTokens.fabStopRing, CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .size(RecordChromeTokens.fabSize)
                .clip(CircleShape)
                .background(fill)
                .border(1.5.dp, stroke, CircleShape)
                .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
                .semantics { contentDescription = semanticsLabel },
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                RecordFabState.Stop -> Box(
                    Modifier
                        .size(RecordChromeTokens.stopSquareSize)
                        .clip(RoundedCornerShape(RecordChromeTokens.stopSquareRadius))
                        .background(RecordChromeTokens.stopSquare),
                )
                RecordFabState.Start -> Icon(RecordChromeIcons.fabPlay, contentDescription = null, tint = Color.White.copy(alpha = 0.78f), modifier = Modifier.size(22.dp))
                RecordFabState.Disabled -> Icon(RecordChromeIcons.fabPlay, contentDescription = null, tint = Color.White.copy(alpha = 0.25f), modifier = Modifier.size(22.dp))
            }
        }
    }
}

/**
 * Low-key recovery nudge on the Record idle screen (a chip near the status pill). Recovery
 * *cards* live on the Library (the replan / ADR 0005); this is just the landing-screen ping.
 * Replaces the visual of the old RecoveryEchoBanner; RecordScreen still computes [count] from
 * RovaApp.recoveryReport via RecoveryViewSource.eligibleSessionCount.
 */
@Composable
fun RecordRecoveryChip(count: Int, onReview: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable { onReview() },
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.40f),
        contentColor = Color.White,
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(Icons.Default.HistoryIcon, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
            val word = if (count == 1) "recording" else "recordings"
            Text("$count $word interrupted · Review", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
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
internal fun loopPillContent(loopIndex: Int, loopTotal: Int): String? = when {
    loopTotal == 1 || loopTotal == 0 -> null   // single-clip or zero-clip — hide the pill
    loopTotal < 0  -> "${loopIndex.coerceAtLeast(0)} loops done"
    else           -> "${loopIndex.coerceIn(0, loopTotal)}/$loopTotal loops done"
}

internal enum class StatusDotColor { RECORDING, WAITING, MERGING }

internal data class StatusPillContent(
    val dot: StatusDotColor,
    val main: String,
    val time: String,
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
        main = "Recording",
        time = "· ${RecordHudFormatters.formatMmSs(clipSecondsLeft.toLong())} left",
    )
    RecordHudState.Waiting -> StatusPillContent(
        dot = StatusDotColor.WAITING,
        main = "On break",
        time = "· next in ${RecordHudFormatters.formatMmSs(waitSecondsLeft.toLong())}",
    )
    is RecordHudState.Merging -> StatusPillContent(
        dot = StatusDotColor.MERGING,
        main = "Merging…",
        time = "· ${(state.progress * 100).toInt().coerceIn(0, 100)}%",
    )
    RecordHudState.Idle ->
        error("hudStatusPillContent called with Idle — caller bug; gate on hudState != Idle")
}

// ── R2 active-HUD composables (Task 8). Consume the Phase-A helpers above. ──

@Composable
private fun StatusDot(dot: StatusDotColor, modifier: Modifier = Modifier) {
    val color = when (dot) {
        StatusDotColor.RECORDING -> RecordChromeTokens.dotRecording
        StatusDotColor.WAITING   -> RecordChromeTokens.dotBreak   // slate #94A3B8 — mockup .dot-break
        StatusDotColor.MERGING   -> MergingDotColor
    }
    if (dot == StatusDotColor.RECORDING) {
        // mockup `.dot-recording` — blink 1.8s ease-in-out + a red glow.
        // Compose has no box-shadow; the glow is a radial-gradient halo drawn
        // behind the dot, pulsing on the same transition as the dot alpha.
        val transition = rememberInfiniteTransition(label = "recordingDot")
        val pulse by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "recordingDotPulse",
        )
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
private fun LoopPill(loopIndex: Int, loopTotal: Int, modifier: Modifier = Modifier) {
    // loopPillContent is the tested (untouched) hide-gate: null ⇒ single-clip /
    // zero-clip ⇒ no pill. The mockup splits the body into a numeral + a caption,
    // so the numeral is re-derived here with the same clamp loopPillContent uses.
    loopPillContent(loopIndex, loopTotal) ?: return
    val numeral = if (loopTotal < 0) {
        "${loopIndex.coerceAtLeast(0)}"
    } else {
        "${loopIndex.coerceIn(0, loopTotal)}/$loopTotal"
    }
    Surface(
        modifier = modifier,
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
            Text("LOOPS DONE", style = RovaTokens.loopUnit, color = RecordChromeTokens.loopUnitText)
        }
    }
}

@Composable
private fun StatusPill(content: StatusPillContent, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
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
            StatusDot(content.dot)
            Text(
                content.main,
                style = RovaTokens.statusMain,
                color = RecordChromeTokens.statusMainText,
            )
            Text(
                content.time,
                style = RovaTokens.statusTime,
                color = RecordChromeTokens.statusTimeText,
            )
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
@Composable
internal fun RecordActiveHud(
    state: RecordHudState,
    loopIndex: Int,
    loopTotal: Int,
    clipSecondsLeft: Int,
    waitSecondsLeft: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LoopPill(loopIndex = loopIndex, loopTotal = loopTotal)
        StatusPill(content = hudStatusPillContent(state, clipSecondsLeft, waitSecondsLeft))
    }
}
