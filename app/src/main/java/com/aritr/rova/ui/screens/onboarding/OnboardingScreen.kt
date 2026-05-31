package com.aritr.rova.ui.screens.onboarding

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aritr.rova.RovaApp

/**
 * M4 (2026-05-27) Onboarding redesign — top-level composable for the
 * first-launch onboarding route. Three ActivityResult launchers
 * (Camera, Mic, Notifications) requested up-front via per-permission
 * cards (2026-05-31). SCHEDULE_EXACT_ALARM stays JIT — no in-app
 * dialog exists. The step set is `viewModel.visibleSteps`
 * (Notifications only on API 33+).
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
 * Owner feedback 2026-05-27 (post-smoke):
 *  - Immersive sticky chrome: system bars hidden on mount, restored on
 *    dismount; transient swipe-from-edge reappearance (Android default
 *    BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE). Pairs with the
 *    `windowInsetsPadding(WindowInsets.safeDrawing)` in
 *    [OnboardingSlide] — when bars are hidden the insets collapse to
 *    zero (full-bleed); when transiently shown the content shifts to
 *    clear them.
 *  - Swipe navigation between pages via [HorizontalPager]. VM is the
 *    source of truth: tap-driven transitions (advance/goBack/skip)
 *    drive the pager via the first sync `LaunchedEffect`; user swipes
 *    settle and the second sync `LaunchedEffect` writes back into the
 *    VM via [OnboardingViewModel.setStep]. Pager forward-swipe is
 *    clamped at the last visible step (no completion via swipe — user
 *    must tap Allow or Skip for now on the final permission card).
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

    // Immersive sticky — hide status + nav bars while onboarding is on
    // screen. Bars reappear transiently on swipe-from-edge then auto-hide.
    // Restored on dismount so Record + History pick up normal chrome.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.let {
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

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

    val steps = viewModel.visibleSteps
    val permSteps = remember(steps) { permissionStepsOf(steps) }

    val pagerState = rememberPagerState(
        initialPage = steps.indexOf(state.step).coerceAtLeast(0),
        pageCount = { steps.size }
    )

    // Sync 1: VM step → pager.
    LaunchedEffect(state.step) {
        val target = steps.indexOf(state.step)
        if (target >= 0 && pagerState.currentPage != target) {
            pagerState.animateScrollToPage(target)
        }
    }

    // Sync 2: user swipe → VM (idempotent on equal step).
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            viewModel.setStep(steps[pagerState.currentPage])
        }
    }

    val canGoBack = steps.indexOf(state.step) > 0 && !state.completed
    BackHandler(enabled = canGoBack) { viewModel.goBack() }

    HorizontalPager(state = pagerState) { pageIndex ->
        val pageStep = steps[pageIndex]
        when {
            pageStep.isWalkthrough -> WalkthroughSlide(
                step = pageStep,
                onNext = viewModel::advance,
                onSkip = viewModel::skipWalkthroughToCamera
            )
            pageStep.isPermission -> PermissionSlide(
                step = pageStep,
                permIndex = permSteps.indexOf(pageStep),
                permTotal = permSteps.size,
                onAllow = {
                    when (pageStep) {
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
                        else -> viewModel.advance()
                    }
                },
                onSkip = viewModel::advance
            )
        }
    }
}
