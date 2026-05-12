package com.aritr.rova.ui.warnings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aritr.rova.RovaApp
import com.aritr.rova.ui.screens.BatteryOptimizationHelper

/**
 * Where a given [WarningId] is surfaced on the Record screen (ADR 0007). The
 * [WarningCenterViewModel] still resolves the single highest-priority active
 * warning; this only decides how that warning is drawn.
 *
 * - [HardBlockSheet]   — recording can't start / must abort (camera perm, exact
 *                        alarm, storage). Modal, auto-presents, collapses to a
 *                        WarningChip on "Not now"; FAB goes Disabled, nav dims.
 * - [SoftSheet]        — degraded but recordable (mic denied → video-only). Modal,
 *                        secondary action "Continue without audio" dismisses to a chip.
 * - [AdvisorySheet]    — informational (notifications off, battery-opt on, power-save).
 *                        Modal, secondary "Not now" dismisses to a chip; never blocks Start.
 * - [TopBanner]        — mid-recording risks (thermal, low/critical battery, camera in
 *                        use/disabled). Rendered as a top banner over the active
 *                        viewfinder, not a sheet. R1 wires the idle-reachable surfaces
 *                        (sheets/chips); the top-banner render path lands with R2 unless
 *                        trivially cheap here (spec A6).
 */
enum class WarningSurface { HardBlockSheet, SoftSheet, AdvisorySheet, TopBanner }

fun warningSurfaceFor(id: WarningId): WarningSurface = when (id) {
    WarningId.CAMERA_PERMISSION_DENIED, WarningId.EXACT_ALARM_DENIED, WarningId.STORAGE_INSUFFICIENT -> WarningSurface.HardBlockSheet
    WarningId.MICROPHONE_DENIED -> WarningSurface.SoftSheet
    WarningId.NOTIFICATIONS_DENIED, WarningId.BATTERY_OPTIMIZATION_ON, WarningId.POWER_SAVE_MODE -> WarningSurface.AdvisorySheet
    WarningId.THERMAL_SHUTDOWN, WarningId.THERMAL_EMERGENCY, WarningId.THERMAL_CRITICAL, WarningId.THERMAL_SEVERE, WarningId.THERMAL_MODERATE,
    WarningId.BATTERY_CRITICAL, WarningId.BATTERY_LOW, WarningId.CAMERA_IN_USE, WarningId.CAMERA_DISABLED -> WarningSurface.TopBanner
}

/**
 * R1 — Warning surface entry point. Mounted on the Record screen. Shows the single
 * highest-priority active warning as a [WarningSheet] (or collapses to a [WarningChip]
 * after the user dismisses). [WarningSurface.TopBanner] warnings are mid-recording
 * (R2 territory) — rendered as nothing in this slice.
 *
 * Under preview / non-RovaApp contexts ([applicationContext] is not a [RovaApp]),
 * renders nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarningCenter(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as? RovaApp } ?: return
    val vm: WarningCenterViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                WarningCenterViewModel(
                    cameraPermissionGranted = app.cameraPermissionSignal.state,
                    exactAlarmGranted = app.exactAlarmSignal.state,
                    storageInsufficient = app.storageSignal.insufficientToStart,
                    thermal = app.thermalStatusSignal.state,
                    power = app.powerSignal.state,
                    camera = app.cameraStateSignal.state,
                    microphonePermissionGranted = app.microphonePermissionSignal.state,
                    notificationsGranted = app.notificationPermissionSignal.state,
                    batteryOptimizationExempt = app.batteryOptimizationSignal.isExempt
                )
            }
        }
    )
    val active by vm.activeWarning.collectAsStateWithLifecycle()
    val id = active ?: return

    val surface = warningSurfaceFor(id)
    // R1: TopBanner-tier warnings are mid-recording (R2 territory) — render nothing for them here
    // unless the cheap top-banner path is added in this slice (spec A6). Default: no-op.
    if (surface == WarningSurface.TopBanner) return

    // Dismiss/collapse state — per active id, so a different warning re-presents fresh.
    var collapsed by rememberSaveable(id) { mutableStateOf(false) }

    if (collapsed) {
        WarningChip(id = id, onExpand = { collapsed = false }, modifier = modifier)
    } else {
        WarningSheet(
            id = id,
            surface = surface,
            onPrimary = { launchActionTarget(context, warningSheetContent(id).primary.target); collapsed = true },
            onSecondary = { collapsed = true },     // "Not now" / "Continue without audio" → collapse to a chip
            onDismissRequest = {
                if (surface != WarningSurface.HardBlockSheet) collapsed = true
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WarningSheet(
    id: WarningId,
    surface: WarningSurface,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val c = warningSheetContent(id)
    val accent = when (surface) {
        WarningSurface.HardBlockSheet -> MaterialTheme.colorScheme.error
        WarningSurface.SoftSheet -> Color(0xFFFBBF24)            // amber — or a tokens-doc token if defined
        WarningSurface.AdvisorySheet -> MaterialTheme.colorScheme.primary
        WarningSurface.TopBanner -> MaterialTheme.colorScheme.primary // unreachable here
    }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { surface != WarningSurface.HardBlockSheet || it != SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(accent.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) { Icon(c.icon, contentDescription = null, tint = accent) }
            Text(c.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (c.body.isNotBlank()) {
                Text(c.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) { Text(c.primary.label) }
            c.secondary?.let { sec ->
                TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) { Text(sec.label) }
            }
        }
    }
}

@Composable
private fun WarningChip(id: WarningId, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val c = warningSheetContent(id)
    Surface(
        modifier = modifier.clickable { onExpand() },
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.40f),
        contentColor = Color.White,
    ) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(c.icon, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
            Text(c.title, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
        }
    }
}

internal data class WarningAction(val label: String, val target: ActionTarget)

internal enum class ActionTarget {
    EXACT_ALARM_SETTINGS, BATTERY_OPTIMIZATION, NOTIFICATION_SETTINGS, APP_DETAILS_SETTINGS
}

internal data class WarningSheetContent(
    val icon: ImageVector,
    val title: String,
    /** Short supporting line; never blank for sheet-rendered warnings. Final copy is the dev's call. */
    val body: String,
    /** Primary CTA — always present (e.g. "Open App Settings"). */
    val primary: WarningAction,
    /** Secondary CTA — present for HardBlock/Soft/Advisory sheets ("Not now" / "Continue without audio"); may be null for TopBanner. */
    val secondary: WarningAction?,
)

/**
 * The 16-arm sheet-content map (ADR 0007). Copy mirrors `mockups/new_uiux/07-warnings.html`.
 * Icons reuse the ones the old banner carried. Replaces `bannerContent` now that [WarningSheet] is live.
 */
internal fun warningSheetContent(id: WarningId): WarningSheetContent = when (id) {
    WarningId.CAMERA_PERMISSION_DENIED -> WarningSheetContent(
        Icons.Default.NoPhotography, "Camera access required",
        "Rova can't record without camera access. Grant the permission in App Settings to continue.",
        WarningAction("Open App Settings", ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction("Not now", ActionTarget.APP_DETAILS_SETTINGS),
    )
    WarningId.EXACT_ALARM_DENIED -> WarningSheetContent(
        Icons.Default.AlarmOff, "Alarm permission required",
        "Rova uses exact alarms to time recording segments. Without it, clips won't start or stop on schedule.",
        WarningAction("Allow exact alarms", ActionTarget.EXACT_ALARM_SETTINGS),
        WarningAction("Not now", ActionTarget.EXACT_ALARM_SETTINGS),
    )
    WarningId.STORAGE_INSUFFICIENT -> WarningSheetContent(
        Icons.Default.Storage, "Not enough storage to start",
        "Free up space, then try again.",
        WarningAction("Free up space", ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction("Not now", ActionTarget.APP_DETAILS_SETTINGS),
    )
    WarningId.MICROPHONE_DENIED -> WarningSheetContent(
        Icons.Default.MicOff, "Recording without audio",
        "This session will record video only. You can grant microphone access in Settings and try again.",
        WarningAction("Grant microphone access", ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction("Continue without audio", ActionTarget.APP_DETAILS_SETTINGS),
    )
    WarningId.NOTIFICATIONS_DENIED -> WarningSheetContent(
        Icons.Default.NotificationsOff, "Stay in the loop",
        "Enable notifications to see when recording starts, stops, or finishes merging — even with the screen off.",
        WarningAction("Enable notifications", ActionTarget.NOTIFICATION_SETTINGS),
        WarningAction("Not now", ActionTarget.NOTIFICATION_SETTINGS),
    )
    WarningId.BATTERY_OPTIMIZATION_ON -> WarningSheetContent(
        Icons.Default.BatterySaver, "Battery optimization may stop recording",
        "Android may kill Rova in the background. Disable battery optimization for reliable long sessions.",
        WarningAction("Disable", ActionTarget.BATTERY_OPTIMIZATION),
        WarningAction("Not now", ActionTarget.BATTERY_OPTIMIZATION),
    )
    WarningId.POWER_SAVE_MODE -> WarningSheetContent(
        Icons.Default.PowerSettingsNew, "Power-save mode may throttle recording",
        "Turning off battery saver gives Rova full CPU/IO for the session.",
        WarningAction("Settings", ActionTarget.APP_DETAILS_SETTINGS),
        WarningAction("Not now", ActionTarget.APP_DETAILS_SETTINGS),
    )
    WarningId.THERMAL_SHUTDOWN -> WarningSheetContent(Icons.Default.Thermostat, "Device overheating — recording stopped", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_EMERGENCY -> WarningSheetContent(Icons.Default.Thermostat, "Device critically hot", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_CRITICAL -> WarningSheetContent(Icons.Default.Thermostat, "Device very hot — recording may stop", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_SEVERE -> WarningSheetContent(Icons.Default.Thermostat, "Device hot — quality may drop", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.THERMAL_MODERATE -> WarningSheetContent(Icons.Default.Thermostat, "Device warming up", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.BATTERY_CRITICAL -> WarningSheetContent(Icons.Default.BatteryAlert, "Battery critical — recording may stop", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.BATTERY_LOW -> WarningSheetContent(Icons.Default.BatteryAlert, "Battery low — consider charging", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.CAMERA_IN_USE -> WarningSheetContent(Icons.Default.VideocamOff, "Camera in use by another app", "Close the other camera app.", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
    WarningId.CAMERA_DISABLED -> WarningSheetContent(Icons.Default.VideocamOff, "Camera disabled by device policy", "", WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null)
}

private fun launchActionTarget(context: Context, target: ActionTarget) {
    val pkgUri = Uri.fromParts("package", context.packageName, null)
    val intent: Intent = when (target) {
        ActionTarget.EXACT_ALARM_SETTINGS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)
            else
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        ActionTarget.BATTERY_OPTIMIZATION ->
            BatteryOptimizationHelper.buildRequestIntent(context.packageName)
        ActionTarget.NOTIFICATION_SETTINGS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        ActionTarget.APP_DETAILS_SETTINGS ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
    }
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {}
}
