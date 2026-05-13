package com.aritr.rova.ui.screens

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
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraIos
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.History as HistoryIcon
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aritr.rova.service.RovaRecordingService
import com.aritr.rova.ui.components.RecordHudState

// Screen-local style constants (see mockups/new_uiux/01-record-home.html .status-pill / .loop-pill;
// docs/UI_DESIGN_TOKENS.md decides any of these the tokens doc promotes to MaterialTheme.*).
private val GlassFill = Color.Black.copy(alpha = 0.40f)
private val GlassStroke = Color.White.copy(alpha = 0.07f)
private val StatusPillShape = RoundedCornerShape(20.dp)
private val PillShape = RoundedCornerShape(11.dp)
private val ControlBtnSize = 30.dp          // visible glass-circle diameter
private val ControlBtnTouchSize = 48.dp     // a11y touch target (the glass circle is centered inside)

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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hudState is RecordHudState.Recording || hudState is RecordHudState.Waiting) {
            Row(
                modifier = Modifier
                    .clip(PillShape)
                    .background(GlassFill)
                    .border(1.dp, GlassStroke, PillShape)
                    .padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "${currentLoop.coerceAtLeast(0)}/${totalLoops.coerceAtLeast(0)}",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.93f),
                )
                Text(
                    text = "loops done",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.32f),
                )
            }
        }
        Row(
            modifier = Modifier
                .clip(StatusPillShape)
                .background(GlassFill)
                .border(1.dp, GlassStroke, StatusPillShape)
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            StatusDot(hudState)
            Text(statusText, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.65f))
            if (statusDetail != null) {
                Text("· $statusDetail", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.32f))
            }
        }
    }
}

@Composable
private fun StatusDot(hudState: RecordHudState) {
    val color = when (hudState) {
        RecordHudState.Recording -> Color(0xFFEF4444)
        else -> Color.White.copy(alpha = 0.25f)
    }
    Box(Modifier.size(6.dp).clip(CircleShape).background(color))
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
) {
    val tint = if (enabled) Color.White.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.3f)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        GlassCircleButton(onClick = onCycleFlash, enabled = enabled) {
            val (icon, contentTint) = when (flashMode) {
                RovaRecordingService.FLASH_MODE_ON -> Icons.Default.FlashOn to (if (enabled) Color.Yellow else tint)
                RovaRecordingService.FLASH_MODE_AUTO -> Icons.Default.FlashAuto to tint
                else -> Icons.Default.FlashOff to tint
            }
            Icon(icon, contentDescription = "Flash", tint = contentTint)
        }
        GlassCircleButton(onClick = onFlip, enabled = enabled) {
            Icon(Icons.Default.FlipCameraIos, contentDescription = "Flip camera", tint = tint)
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
                .size(ControlBtnSize)
                .clip(CircleShape)
                .background(GlassFill)
                .border(1.dp, GlassStroke, CircleShape),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

// ── RecordSettingsCard style constants ──
private val SettingsCardShape = RoundedCornerShape(14.dp)
private val SettingsCardFill = Color.White.copy(alpha = 0.065f)
private val SettingsCardStroke = Color.White.copy(alpha = 0.09f)
private val CellDivider = Color.White.copy(alpha = 0.07f)

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
    onOpenSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        // swipe-to-edit hint
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(bottom = 1.dp)) {
            Box(Modifier.width(30.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.22f)))
            Text("SWIPE TO EDIT", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.22f))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)              // a11y minimum touch target
                .clip(SettingsCardShape)
                .background(SettingsCardFill)
                .border(1.dp, SettingsCardStroke, SettingsCardShape)
                .clickable { onOpenSheet() }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount -> if (dragAmount < -8f) onOpenSheet() }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
            SettingsCell("Mode", recordModeValue(), Modifier.weight(1f), readOnly = true)
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Edit session settings", tint = Color.White.copy(alpha = 0.18f), modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun SettingsCell(key: String, value: String, modifier: Modifier, readOnly: Boolean) {
    Column(modifier = modifier.padding(horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.labelLarge, color = if (readOnly) Color.White.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.88f), textAlign = TextAlign.Center, maxLines = 1)
        Text(key.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.28f), textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun CellSep() {
    Box(Modifier.width(1.dp).height(22.dp).background(CellDivider))
}

// ── RecordBottomNav style constants ──
private val BottomNavFill = Color.Black.copy(alpha = 0.50f)
private val BottomNavStroke = Color.White.copy(alpha = 0.055f)
private val FabSize = 56.dp

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
            .background(BottomNavFill)
            .border(width = 1.dp, color = BottomNavStroke, shape = RoundedCornerShape(0.dp))
            .windowInsetsPadding(WindowInsets.navigationBars)   // clear the gesture-nav bar
            .padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        NavItem(icon = Icons.Default.PhotoLibrary, label = "Library", enabled = !navItemsLocked, onClick = onLibrary)
        RecordFab(state = fabState, onClick = onFabClick)
        NavItem(icon = Icons.Default.SettingsIcon, label = "Settings", enabled = !navItemsLocked, onClick = onSettings)
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = if (enabled) Modifier.clickable { onClick() } else Modifier,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White.copy(alpha = if (enabled) 0.4f else 0.14f), modifier = Modifier.size(34.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = if (enabled) 0.32f else 0.12f))
    }
}

@Composable
private fun RecordFab(state: RecordFabState, onClick: () -> Unit) {
    val (fill, stroke, semanticsLabel) = when (state) {
        RecordFabState.Start -> Triple(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.15f), "Start recording")
        RecordFabState.Stop -> Triple(Color(0xFFEF4444).copy(alpha = 0.13f), Color(0xFFEF4444).copy(alpha = 0.30f), "Stop recording")
        RecordFabState.Disabled -> Triple(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.08f), "Start recording (unavailable)")
    }
    val enabled = state != RecordFabState.Disabled
    Box(
        modifier = Modifier
            .size(FabSize)
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, stroke, CircleShape)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .semantics { contentDescription = semanticsLabel },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            RecordFabState.Stop -> Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFEF4444)))
            RecordFabState.Start -> Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.78f), modifier = Modifier.size(26.dp))
            RecordFabState.Disabled -> Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.25f), modifier = Modifier.size(26.dp))
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
