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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aritr.rova.RovaApp
import com.aritr.rova.ui.components.RecordHudState
import com.aritr.rova.ui.screens.BatteryOptimizationHelper

/** R1/R2 amber accent (soft-sheet body + top-banner). File-local — RovaTokens migration is later. */
private val AmberWarning = Color(0xFFFBBF24)

/**
 * R2 — Warning surface entry point. Mounted on the Record screen. Routes the single
 * highest-priority active warning to the correct surface based on [hudState]:
 *
 * - [RecordHudState.Idle] → idle branch: sheet / chip path (R1 behaviour).
 *   [WarningSurface.TopBanner] ids continue to no-op here (mid-recording only).
 * - any other [hudState] → active branch: renders [WarningTopBanner] for
 *   [WarningSurface.TopBanner]-mapped ids; sheet / chip ids suppress (no-op).
 *
 * Under preview / non-RovaApp contexts ([applicationContext] is not a [RovaApp]),
 * renders nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarningCenter(
    hudState: RecordHudState,
    onStopRecording: () -> Unit,            // required — every call site must declare intent
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
                    storageLowMidRec = app.storageLowMidRecSignal.isLow,        // ← NEW (R2 T5)
                )
            }
        }
    )
    val active by vm.activeWarning.collectAsStateWithLifecycle()
    val id = active ?: return

    val surface = warningSurfaceFor(id)

    if (hudState is RecordHudState.Idle) {
        // Idle branch — sheet / chip path (R1). TopBanner-mapped ids continue to no-op here.
        if (surface == WarningSurface.TopBanner) return

        // Dismiss state is owned by the ViewModel, NOT composable-local
        // rememberSaveable — see WarningCenterViewModel.dismissedWarnings.
        // A composable-local collapse flag was discarded on every
        // WarningCenter unmount / early-return slot discard, re-presenting
        // the sheet endlessly (2026-05-20 "keeps asking" bug).
        val dismissed by vm.dismissedWarnings.collectAsStateWithLifecycle()

        if (id in dismissed) {
            WarningSnoozeChip(id = id, onExpand = { vm.restore(id) }, modifier = modifier)
        } else {
            WarningSheet(
                id = id,
                surface = surface,
                onPrimary = { launchActionTarget(context, warningSheetContent(id).primary.target); vm.dismiss(id) },
                onSecondary = { vm.dismiss(id) },     // "Not now" / "Continue without audio" → collapse to a chip
                onDismissRequest = {
                    if (surface != WarningSurface.HardBlockSheet) vm.dismiss(id)
                },
            )
        }
    } else {
        // Active branch (Recording / Waiting / Merging) — TopBanner only; sheets / chips suppress.
        if (surface != WarningSurface.TopBanner) return
        WarningTopBanner(
            content = midRecBannerContent(id),
            onAction = onStopRecording,
            modifier = modifier,
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
        WarningSurface.SoftSheet -> AmberWarning
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

/**
 * R2 — Mid-recording amber top banner (ADR 0007, mockups/new_uiux/07-warnings.html row 6).
 * Rounded glass surface, leading icon + two-line text block (title + sub), trailing Stop CTA pill.
 * Only shown in the active branch (Recording / Waiting / Merging) for [WarningSurface.TopBanner] ids.
 */
@Composable
private fun WarningTopBanner(
    content: TopBannerContent,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                content.icon, contentDescription = null,
                tint = AmberWarning, modifier = Modifier.size(18.dp),
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    content.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    content.sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
            Surface(
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)        // a11y floor (R1 cleanup pass convention)
                    .clickable { onAction() },
                shape = RoundedCornerShape(10.dp),
                color = AmberWarning.copy(alpha = 0.20f),
                contentColor = AmberWarning,
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxHeight()) {
                    Text(
                        content.cta,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
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
        // VM-only target — should be routed by [WarningCenterViewModel.snoozeForever]
        // before reaching here. Defensive no-op keeps the `when` exhaustive without
        // launching an unrelated Intent if a future caller misroutes it.
        ActionTarget.SNOOZE_FOREVER -> return
    }
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {}
}
