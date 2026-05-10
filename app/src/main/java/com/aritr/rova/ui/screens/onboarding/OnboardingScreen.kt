package com.aritr.rova.ui.screens.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aritr.rova.RovaApp

/**
 * Phase 2.6 — top-level Composable for the first-launch onboarding
 * route. Owns:
 *  - the four [rememberLauncherForActivityResult] permission launchers
 *    (Camera + Mic + Notifications via [ActivityResultContracts.RequestPermission],
 *    Exact-Alarm via [ActivityResultContracts.StartActivityForResult] —
 *    `SCHEDULE_EXACT_ALARM` is granted through a system-settings page,
 *    not a runtime permission dialog)
 *  - SDK-version gating (POST_NOTIFICATIONS API 33+, SCHEDULE_EXACT_ALARM
 *    API 31+ — auto-advance via [LaunchedEffect] on unsupported devices
 *    so the slide never renders for a permission the OS doesn't gate)
 *  - the back-gesture contract from UI_NAV_GRAPH §4.1: back walks
 *    slides backward; on slide 1 the system handles back (exits the app)
 *
 * Permission denial does **not** block onboarding completion (Phase 2.6
 * brief). Both grant and denial routes through the launcher's onResult
 * which calls [OnboardingViewModel.advance] — denial state is observed
 * later by the WarningCenter (Phase 4) on the record screen.
 *
 * The route is registered in [com.aritr.rova.ui.MainScreen] as the
 * conditional start destination when [com.aritr.rova.data.RovaSettings.onboardingCompleted]
 * is `false`. The bottom-nav pill is hidden via the existing Phase 2.5
 * `TOP_LEVEL_ROUTES` guard (onboarding intentionally absent from that set).
 */
@Composable
fun OnboardingScreen(
    onCompleted: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as RovaApp
    val viewModel: OnboardingViewModel = viewModel(factory = OnboardingViewModel.factory(app))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.completed) {
        if (state.completed) onCompleted()
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> viewModel.advance() }
    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> viewModel.advance() }
    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> viewModel.advance() }
    val alarmLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ -> viewModel.advance() }

    // SDK gating: POST_NOTIFICATIONS landed in Tiramisu (API 33);
    // SCHEDULE_EXACT_ALARM landed in S (API 31). Below those, the
    // permission either does not exist or is granted silently —
    // skip the slide so the user does not face a CTA that has no
    // corresponding system surface.
    LaunchedEffect(state.step) {
        when (state.step) {
            OnboardingStep.PERM_NOTIFS ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) viewModel.advance()
            OnboardingStep.PERM_ALARM ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) viewModel.advance()
            else -> Unit
        }
    }

    val canGoBack = state.step.previous() != null && !state.completed
    BackHandler(enabled = canGoBack) { viewModel.goBack() }

    when {
        state.step.isWalkthrough -> WalkthroughSlide(
            step = state.step,
            onNext = viewModel::advance,
            onSkip = viewModel::complete
        )
        state.step.isPermission -> PermissionSlide(
            step = state.step,
            onAllow = {
                when (state.step) {
                    OnboardingStep.PERM_CAMERA ->
                        cameraLauncher.launch(Manifest.permission.CAMERA)
                    OnboardingStep.PERM_MIC ->
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    OnboardingStep.PERM_NOTIFS ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.advance()
                        }
                    OnboardingStep.PERM_ALARM ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.fromParts("package", context.packageName, null))
                            alarmLauncher.launch(intent)
                        } else {
                            viewModel.advance()
                        }
                    else -> viewModel.advance()
                }
            },
            onSkip = viewModel::advance
        )
    }
}
