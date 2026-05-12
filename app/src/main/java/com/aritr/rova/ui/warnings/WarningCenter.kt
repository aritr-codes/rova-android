package com.aritr.rova.ui.warnings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
 * Phase 4.1 — the WarningCenter banner. Mounted on the Record screen
 * (Phase 4.2 routes the same [WarningId] set to other surfaces). Shows
 * the single highest-priority active warning, or nothing. Always visible
 * when a warning is active — no hudState gating, no dismiss/snooze in
 * this slice (the banner follows the signal; it clears when the condition
 * clears).
 *
 * Under preview / non-RovaApp contexts (`applicationContext` is not a
 * [RovaApp]), renders nothing.
 */
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
    active?.let { WarningBanner(id = it, modifier = modifier) }
}

@Composable
private fun WarningBanner(id: WarningId, modifier: Modifier) {
    val context = LocalContext.current
    val content = bannerContent(id)
    val container = when (id.tier) {
        WarningTier.HARD_BLOCK, WarningTier.CRITICAL -> MaterialTheme.colorScheme.errorContainer
        WarningTier.ADVISORY -> MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainer = when (id.tier) {
        WarningTier.HARD_BLOCK, WarningTier.CRITICAL -> MaterialTheme.colorScheme.onErrorContainer
        WarningTier.ADVISORY -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = container,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(content.icon, contentDescription = null, tint = onContainer)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    content.title,
                    color = onContainer,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (content.body.isNotBlank()) {
                    Text(
                        content.body,
                        color = onContainer.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            content.action?.let { action ->
                TextButton(onClick = { launchActionTarget(context, action.target) }) {
                    Text(action.label, color = onContainer)
                }
            }
        }
    }
}

private data class BannerContent(
    val icon: ImageVector,
    /** Verbatim from the NEW_UI_BACKEND_REPLAN §"Phase 4" precedence table. */
    val title: String,
    /** Short supporting line; empty string = title only. Final copy is the dev's call. */
    val body: String,
    val action: WarningAction?
)

private data class WarningAction(val label: String, val target: ActionTarget)

private enum class ActionTarget {
    EXACT_ALARM_SETTINGS, BATTERY_OPTIMIZATION, NOTIFICATION_SETTINGS, APP_DETAILS_SETTINGS
}

/**
 * The 16-arm content map — all 16 rows are now reachable from [WarningPrecedence.resolve] (Phase 4.1b wired #1/#3/#12).
 * Icons are sensible Material defaults — for
 * [WarningId.BATTERY_OPTIMIZATION_ON] the icon mirrors the
 * battery-themed icon the old `BatteryOptimizationBanner` carried.
 */
private fun bannerContent(id: WarningId): BannerContent = when (id) {
    WarningId.CAMERA_PERMISSION_DENIED -> BannerContent(
        Icons.Default.NoPhotography, "Camera access required",
        "Rova can't record without camera permission.",
        WarningAction("Settings", ActionTarget.APP_DETAILS_SETTINGS)
    )
    WarningId.EXACT_ALARM_DENIED -> BannerContent(
        Icons.Default.AlarmOff, "Exact alarms disabled — periodic recording can't run",
        "Loop timing falls back to inexact and may drift.",
        WarningAction("Allow", ActionTarget.EXACT_ALARM_SETTINGS)
    )
    WarningId.STORAGE_INSUFFICIENT -> BannerContent(
        Icons.Default.Storage, "Not enough storage to start",
        "Free up space to record.",
        WarningAction("Free space", ActionTarget.APP_DETAILS_SETTINGS)
    )
    WarningId.THERMAL_SHUTDOWN -> BannerContent(
        Icons.Default.Thermostat, "Device overheating — recording stopped", "", null
    )
    WarningId.THERMAL_EMERGENCY -> BannerContent(
        Icons.Default.Thermostat, "Device critically hot", "", null
    )
    WarningId.THERMAL_CRITICAL -> BannerContent(
        Icons.Default.Thermostat, "Device very hot — recording may stop", "", null
    )
    WarningId.BATTERY_CRITICAL -> BannerContent(
        Icons.Default.BatteryAlert, "Battery critical — recording may stop", "", null
    )
    WarningId.CAMERA_IN_USE -> BannerContent(
        Icons.Default.VideocamOff, "Camera in use by another app",
        "Close the other camera app.", null
    )
    WarningId.CAMERA_DISABLED -> BannerContent(
        Icons.Default.VideocamOff, "Camera disabled by device policy", "", null
    )
    WarningId.BATTERY_LOW -> BannerContent(
        Icons.Default.BatteryAlert, "Battery low — consider charging", "", null
    )
    WarningId.THERMAL_SEVERE -> BannerContent(
        Icons.Default.Thermostat, "Device hot — quality may drop", "", null
    )
    WarningId.MICROPHONE_DENIED -> BannerContent(
        Icons.Default.MicOff, "Recording without audio",
        "Microphone permission is off — clips are video-only.",
        WarningAction("Settings", ActionTarget.APP_DETAILS_SETTINGS)
    )
    WarningId.BATTERY_OPTIMIZATION_ON -> BannerContent(
        Icons.Default.BatterySaver, "Battery optimization may stop recording in the background", "",
        WarningAction("Disable", ActionTarget.BATTERY_OPTIMIZATION)
    )
    WarningId.POWER_SAVE_MODE -> BannerContent(
        Icons.Default.PowerSettingsNew, "Power-save mode may throttle recording", "",
        WarningAction("Settings", ActionTarget.APP_DETAILS_SETTINGS)
    )
    WarningId.THERMAL_MODERATE -> BannerContent(
        Icons.Default.Thermostat, "Device warming up", "", null
    )
    WarningId.NOTIFICATIONS_DENIED -> BannerContent(
        Icons.Default.NotificationsOff, "Notifications off — you won't see recording progress", "",
        WarningAction("Turn on", ActionTarget.NOTIFICATION_SETTINGS)
    )
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
