package com.aritr.rova.ui.warnings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aritr.rova.RovaApp
import com.aritr.rova.ui.components.RecordHudState
import com.aritr.rova.ui.screens.BatteryOptimizationHelper
import com.aritr.rova.ui.theme.RovaWarnings

/**
 * Phase 4 v3 — Warning surface entry point. Routing only; rendering happens
 * in [WarningSheetV3] / [WarningTopBannerV3] / [WarningSnoozeChip].
 *
 * - [RecordHudState.Idle] + non-TopBanner id → sheet (or snooze-chip if dismissed)
 * - active (Recording/Waiting/Merging) + TopBanner id → top banner
 *
 * Spec: docs/superpowers/specs/2026-05-23-phase-4-warning-reskin-v3-design.md §3.7
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarningCenter(
    hudState: RecordHudState,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    batteryOptimizationExempt = app.batteryOptimizationSignal.isExempt,
                    storageLowMidRec = app.storageLowMidRecSignal.isLow,
                )
            }
        }
    )
    val active by vm.activeWarning.collectAsStateWithLifecycle()
    val id = active ?: return
    val surface = warningSurfaceFor(id)

    if (hudState is RecordHudState.Idle) {
        // Idle branch — sheet / chip. TopBanner ids no-op here.
        if (surface == WarningSurface.TopBanner) return

        val dismissed by vm.dismissedWarnings.collectAsStateWithLifecycle()
        if (id in dismissed) {
            WarningSnoozeChip(
                id = id,
                onExpand = { vm.restore(id) },
                modifier = modifier,
            )
            return
        }

        val expandedWhy by vm.expandedWhy.collectAsStateWithLifecycle()
        WarningSheetV3(
            id = id,
            surface = surface,
            expanded = id in expandedWhy,
            onPrimary = {
                launchActionTarget(context, warningSheetContent(id).primary.target)
                vm.dismiss(id)
            },
            onSecondary = { vm.dismiss(id) },
            onOverflow = { target ->
                if (target == ActionTarget.SNOOZE_FOREVER) {
                    vm.snoozeForever(id)
                } else {
                    launchActionTarget(context, target)
                }
            },
            onToggleWhy = { vm.toggleExpandWhy(id) },
            onDismissRequest = {
                if (surface != WarningSurface.HardBlockSheet) vm.dismiss(id)
            },
        )
    } else {
        // Active branch — TopBanner only.
        if (surface != WarningSurface.TopBanner) return
        WarningTopBannerV3(
            content = midRecBannerContent(id),
            severityColor = RovaWarnings.escalating,
            onAction = onStopRecording,
            modifier = modifier,
        )
    }
}

/** Launches the system Intent for [target]. NO-OP for [ActionTarget.SNOOZE_FOREVER]. */
private fun launchActionTarget(context: Context, target: ActionTarget) {
    if (target == ActionTarget.SNOOZE_FOREVER) return
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
        ActionTarget.SNOOZE_FOREVER -> return    // unreachable (guarded above) — for `when` exhaustiveness
    }
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {}
}
