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
import com.aritr.rova.data.RovaSettings
import com.aritr.rova.data.StopReason
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
    // Phase 4.1c — optional shared VM. RecordScreen hoists one instance and
    // passes it to both WarningCenter mounts (idle + active HUD) so the same
    // snoozedForever flow drives both surfaces + the Settings reset row.
    // When null (legacy callers, previews), the factory below constructs one
    // wired straight to RovaSettings.
    vm: WarningCenterViewModel? = null,
    /** Phase 4 Slice 2 — host-wired callback for the echo banner's "Review session" overflow item. Null = item still rendered but tap is a no-op (the underlying ActionTarget.REVIEW_SESSION is a host-nav target). */
    onNavigateToHistory: (() -> Unit)? = null,
    /**
     * Phase 4 Slice 3 — invoked when the user taps the THERMAL_AUTOSTOPPED
     * echo banner's primary CTA ("Tips to cool down"). Host (RecordScreen)
     * flips its rememberSaveable showTipsSheet state to render
     * [ThermalTipsSheet]. Null = no sheet host wired (previews, legacy
     * callers); the CTA becomes a no-op.
     */
    onOpenThermalTips: (() -> Unit)? = null,
    /**
     * Phase 4.3 — invoked when the C2.4 CANT_MERGE sheet's secondary CTA
     * ("Save segments only") is tapped. Receives [pendingCantMergeSessionId].
     * Null = CTA still renders but dispatch is a no-op (back-compat).
     */
    onKeepRawFromSheet: ((sessionId: String) -> Unit)? = null,
    /**
     * Phase 4.3 — invoked when the C2.4 CANT_MERGE sheet's tertiary CTA
     * ("Discard session") is tapped. Receives [pendingCantMergeSessionId].
     * Null = CTA still renders but dispatch is a no-op (back-compat).
     */
    onDiscardFromSheet: ((sessionId: String) -> Unit)? = null,
    /**
     * Phase 4.3 — current pending CANT_MERGE session ID from
     * [WarningCenterViewModel.pendingCantMergeSessionId]. Null when no
     * recovery merge has failed pre-flight.
     */
    pendingCantMergeSessionId: String? = null,
) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as? RovaApp } ?: return
    val resolvedVm: WarningCenterViewModel = vm ?: viewModel(
        factory = viewModelFactory {
            initializer { buildWarningCenterViewModel(app) }
        }
    )
    val active by resolvedVm.activeWarning.collectAsStateWithLifecycle()
    val id = active ?: return
    val surface = warningSurfaceFor(id)

    if (hudState is RecordHudState.Idle) {
        // Idle branch — sheet / chip / echo-banner.
        if (surface == WarningSurface.TopBanner) {
            // Two TopBanner ids render on Idle (echoes of past auto-stops):
            //  • STORAGE_FULL_AUTOSTOPPED (Slice 2) → CTA opens system storage settings.
            //  • THERMAL_AUTOSTOPPED      (Slice 3) → CTA opens ThermalTipsSheet via host.
            // All other TopBanner ids are active-HUD only and suppress here.
            val autoStopEcho by app.autoStopEchoSignal.state.collectAsStateWithLifecycle()
            // Phase 4 Slice 3 follow-up — promote a pending echo over the
            // precedence pick when the pick is an active-state TopBanner id
            // (THERMAL_*/BATTERY_*/CAMERA_IN_USE etc.). Precedence ranks those
            // at rows #4-11 ABOVE the #12-13 echoes; on Idle those ids hit the
            // `else -> Unit` suppression below, swallowing the echo entirely.
            // See [effectiveIdleTopBannerId] KDoc for the precedence rationale.
            val effectiveId = effectiveIdleTopBannerId(id, autoStopEcho)
            when (effectiveId) {
                WarningId.STORAGE_FULL_AUTOSTOPPED -> {
                    WarningTopBannerV3(
                        content = midRecBannerContent(effectiveId),
                        severityColor = RovaWarnings.advisory,
                        onAction = { launchActionTarget(context, ActionTarget.STORAGE_SETTINGS) },
                        onOverflow = { target ->
                            handleEchoOverflow(target, autoStopEcho, resolvedVm, onNavigateToHistory, context)
                        },
                        modifier = modifier,
                    )
                }
                WarningId.THERMAL_AUTOSTOPPED -> {
                    WarningTopBannerV3(
                        content = midRecBannerContent(effectiveId),
                        severityColor = RovaWarnings.advisory,
                        onAction = { onOpenThermalTips?.invoke() },
                        onOverflow = { target ->
                            handleEchoOverflow(target, autoStopEcho, resolvedVm, onNavigateToHistory, context)
                        },
                        modifier = modifier,
                    )
                }
                else -> Unit
            }
            return
        }

        val dismissed by resolvedVm.dismissedWarnings.collectAsStateWithLifecycle()
        if (id in dismissed) {
            WarningSnoozeChip(
                id = id,
                onExpand = { resolvedVm.restore(id) },
                modifier = modifier,
            )
            return
        }

        val expandedWhy by resolvedVm.expandedWhy.collectAsStateWithLifecycle()
        WarningSheetV3(
            id = id,
            surface = surface,
            expanded = id in expandedWhy,
            onPrimary = {
                launchActionTarget(
                    context, warningSheetContent(id).primary.target,
                    pendingCantMergeSessionId = pendingCantMergeSessionId,
                    onKeepRawFromSheet = onKeepRawFromSheet,
                    onDiscardFromSheet = onDiscardFromSheet,
                )
                resolvedVm.dismiss(id)
            },
            onSecondary = {
                // Phase 4.3 — only dispatch secondary target for recovery-specific
                // VM-only targets. Pre-existing "Not now" secondaries point to
                // APP_DETAILS_SETTINGS as a placeholder and must remain dismiss-only.
                val secondaryTarget = warningSheetContent(id).secondary?.target
                if (secondaryTarget == ActionTarget.KEEP_SEGMENTS_ONLY ||
                    secondaryTarget == ActionTarget.DISCARD_RECOVERY_SESSION) {
                    launchActionTarget(
                        context, secondaryTarget,
                        pendingCantMergeSessionId = pendingCantMergeSessionId,
                        onKeepRawFromSheet = onKeepRawFromSheet,
                        onDiscardFromSheet = onDiscardFromSheet,
                    )
                }
                resolvedVm.dismiss(id)
            },
            onTertiary = {
                // Phase 4.3 — tertiary on CANT_MERGE is DISCARD_RECOVERY_SESSION.
                warningSheetContent(id).tertiary?.let { ter ->
                    launchActionTarget(
                        context, ter.target,
                        pendingCantMergeSessionId = pendingCantMergeSessionId,
                        onKeepRawFromSheet = onKeepRawFromSheet,
                        onDiscardFromSheet = onDiscardFromSheet,
                    )
                }
                resolvedVm.dismiss(id)
            },
            onOverflow = { target ->
                if (target == ActionTarget.SNOOZE_FOREVER) {
                    resolvedVm.snoozeForever(id)
                } else {
                    launchActionTarget(context, target)
                }
            },
            onToggleWhy = { resolvedVm.toggleExpandWhy(id) },
            onDismissRequest = {
                if (surface != WarningSurface.HardBlockSheet) resolvedVm.dismiss(id)
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

/**
 * Launches the system Intent for [target]. NO-OP for VM-only / host-nav targets.
 *
 * Phase 4.3 — [onKeepRawFromSheet] / [onDiscardFromSheet] / [pendingCantMergeSessionId]
 * are optional; they are only passed from the sheet call-site for the two recovery targets.
 * Overflow and primary calls that don't concern recovery omit them (default null = no-op).
 */
// Phase 4.2 — bumped from private to internal so HistoryScreen / SettingsScreen
// strip+chip sheet hosts can dispatch warning actions through the same path.
internal fun launchActionTarget(
    context: Context,
    target: ActionTarget,
    pendingCantMergeSessionId: String? = null,
    onKeepRawFromSheet: ((sessionId: String) -> Unit)? = null,
    onDiscardFromSheet: ((sessionId: String) -> Unit)? = null,
) {
    if (target == ActionTarget.SNOOZE_FOREVER) return
    if (target == ActionTarget.DISMISS_AUTOSTOP_ECHO) return
    if (target == ActionTarget.REVIEW_SESSION) return
    if (target == ActionTarget.OPEN_THERMAL_TIPS) return
    if (target == ActionTarget.KEEP_SEGMENTS_ONLY) {
        pendingCantMergeSessionId?.let { sid -> onKeepRawFromSheet?.invoke(sid) }
        return
    }
    if (target == ActionTarget.DISCARD_RECOVERY_SESSION) {
        pendingCantMergeSessionId?.let { sid -> onDiscardFromSheet?.invoke(sid) }
        return
    }
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
        ActionTarget.STORAGE_SETTINGS -> {
            val primary = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            if (primary.resolveActivity(context.packageManager) != null) primary
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        }
        ActionTarget.SNOOZE_FOREVER -> return                    // VM-only; guarded above
        ActionTarget.DISMISS_AUTOSTOP_ECHO -> return             // VM-only; routed by overflow handler
        ActionTarget.REVIEW_SESSION -> return                    // host-nav; routed at call site
        ActionTarget.OPEN_THERMAL_TIPS -> return                 // VM-only; guarded above (Phase 4 Slice 3)
        ActionTarget.KEEP_SEGMENTS_ONLY -> return                // VM-only; dispatched above (Phase 4.3)
        ActionTarget.DISCARD_RECOVERY_SESSION -> return          // VM-only; dispatched above (Phase 4.3)
    }
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {}
}

/**
 * Phase 4 Slice 3 follow-up — pure resolver for the Idle TopBanner branch.
 * Returns the id that SHOULD render on Idle given (a) what
 * `WarningPrecedence.resolve` produced and (b) whether an auto-stop echo
 * is pending in `SessionAutoStopEchoSignal`.
 *
 * Rationale: `WarningPrecedence` ranks active-state thermal/battery/camera
 * TopBanner ids (rows #4-11) ABOVE the auto-stop echoes (rows #12-13). On
 * Idle, the active ids are suppressed (active-HUD-only), so if precedence
 * returned one of them, the echo gets swallowed. This helper promotes the
 * echo to the rendered id in that case — the user keeps the post-stop
 * affordance even if the underlying condition (e.g. device still hot)
 * hasn't cleared yet. When no echo is pending, behaviour is unchanged.
 *
 * Pure-JVM testable per ADR-0007.
 */
internal fun effectiveIdleTopBannerId(
    precedenceId: WarningId,
    autoStopEcho: TerminalEcho?,
): WarningId = when (autoStopEcho?.stopReason) {
    StopReason.LOW_STORAGE -> WarningId.STORAGE_FULL_AUTOSTOPPED
    StopReason.THERMAL -> WarningId.THERMAL_AUTOSTOPPED
    null,
    StopReason.USER, StopReason.PERMISSION_REVOKED,
    StopReason.INIT_FAILED, StopReason.NONE -> precedenceId
}

/**
 * Phase 4 Slice 3 — shared overflow router for the two Idle TopBanner echo
 * arms (STORAGE_FULL_AUTOSTOPPED and THERMAL_AUTOSTOPPED). Factored from the
 * Slice-2 inline lambda so both arms reuse it. `autoStopEcho` may be null
 * if the user dismissed between recompose and tap; in that case
 * DISMISS_AUTOSTOP_ECHO no-ops via the elvis return.
 */
private fun handleEchoOverflow(
    target: ActionTarget,
    autoStopEcho: TerminalEcho?,
    vm: WarningCenterViewModel,
    onNavigateToHistory: (() -> Unit)?,
    context: Context,
) {
    when (target) {
        ActionTarget.DISMISS_AUTOSTOP_ECHO -> {
            val sid = autoStopEcho?.sessionId ?: return
            vm.dismissAutoStopEcho(sid)
        }
        ActionTarget.REVIEW_SESSION -> onNavigateToHistory?.invoke()
        else -> launchActionTarget(context, target)
    }
}

/**
 * Phase 4.1c — single source of truth for constructing a [WarningCenterViewModel]
 * wired to live signals on [RovaApp] AND to the persistent snooze set on
 * [RovaSettings]. Called by both:
 *   - the factory inside [WarningCenter] (when no `vm` is hoisted), and
 *   - the factory inside `RecordScreen` (when one shared VM drives both
 *     WarningCenter mounts + the SettingsSheet reset row).
 *
 * `runCatching { WarningId.valueOf(it) }.getOrNull()` swallows any stale
 * `WarningId.name` left over from a renamed/removed id; `mapNotNull` drops
 * it. The stored set self-heals to the trimmed value on the next write.
 */
internal fun buildWarningCenterViewModel(app: RovaApp): WarningCenterViewModel {
    val settings = RovaSettings(app)
    val initialSnoozed: Set<WarningId> = settings.snoozedWarningIds
        .mapNotNull { runCatching { WarningId.valueOf(it) }.getOrNull() }
        .toSet()
    return WarningCenterViewModel(
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
        autoStopEcho = app.autoStopEchoSignal.state,                  // ← Phase 4 Slice 2
        recoveryMergeOutcomeSignal = app.recoveryMergeOutcomeSignal.state, // ← NEW (Phase 4.3)
        saveFolderUnavailable = app.saveFolderSignal.state,               // ← NEW (B4b ADR-0024)
        initialSnoozedIds = initialSnoozed,
        onSnoozeChanged = { set ->
            settings.snoozedWarningIds = set.map(WarningId::name).toSet()
        },
        onAutoStopDismissed = { sessionId ->                          // ← NEW (Phase 4 Slice 2)
            settings.dismissedAutoStopEchoIds = settings.dismissedAutoStopEchoIds + sessionId
            app.autoStopEchoSignal.markDismissed(sessionId)
        },
    )
}
