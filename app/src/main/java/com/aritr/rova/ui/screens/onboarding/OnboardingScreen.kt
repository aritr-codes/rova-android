package com.aritr.rova.ui.screens.onboarding

import android.Manifest
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
 * M4 (2026-05-27) Onboarding redesign — top-level composable for the
 * first-launch onboarding route. Down from 4 ActivityResult launchers
 * (Phase 2.6) to 1: Camera only. Mic, Notifications, and Exact-Alarm
 * are deferred to JIT in [com.aritr.rova.ui.screens.RecordScreen]
 * (existing `rememberMultiplePermissionsState` path).
 *
 * Back-gesture contract: back walks slides backward; on
 * `WALKTHROUGH_1` the system handles back (exits the app). Same as
 * Phase 2.6.
 *
 * Camera-denied path: the launcher's onResult fires `advance()`
 * regardless of grant/deny. Denial → onboarding completes → user
 * lands on Record → CAMERA_PERMISSION_DENIED WarningCenter advisory
 * (HARD_BLOCK, gates Start) surfaces with a one-tap "Allow" CTA. No
 * nag loop here — Google permission UX guidance, 2025.
 *
 * The route is registered in [com.aritr.rova.ui.MainScreen] as the
 * conditional start destination when
 * [com.aritr.rova.data.RovaSettings.onboardingCompleted] is `false`.
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

    val canGoBack = state.step.previous() != null && !state.completed
    BackHandler(enabled = canGoBack) { viewModel.goBack() }

    when {
        state.step.isWalkthrough -> WalkthroughSlide(
            step = state.step,
            onNext = viewModel::advance,
            onSkip = viewModel::skipWalkthroughToCamera
        )
        state.step.isPermission -> CameraRationaleSlide(
            onAllow = { cameraLauncher.launch(Manifest.permission.CAMERA) },
            onNotNow = viewModel::complete
        )
    }
}
