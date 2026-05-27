package com.aritr.rova.ui.screens.onboarding

import android.Manifest
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
 *    VM via [OnboardingViewModel.setStep]. Pager forward-swipe past
 *    PERM_CAMERA is clamped (no completion via swipe — user must tap
 *    Allow Camera or Not now).
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

    val pagerState = rememberPagerState(
        initialPage = state.step.ordinal,
        pageCount = { OnboardingStep.entries.size }
    )

    // Sync 1: VM step → pager. Tap-driven advance/goBack/skip update VM
    // first; this effect animates the pager to match. Skip from
    // WALKTHROUGH_1 to PERM_CAMERA animates across two pages — natural
    // mobile feel.
    LaunchedEffect(state.step) {
        if (pagerState.currentPage != state.step.ordinal) {
            pagerState.animateScrollToPage(state.step.ordinal)
        }
    }

    // Sync 2: user swipe → VM. When a swipe settles, push the page
    // index back into the VM via setStep (idempotent on equal values,
    // so no-op for tap-driven scrolls where VM is already on target).
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            val target = OnboardingStep.entries[pagerState.currentPage]
            viewModel.setStep(target)
        }
    }

    val canGoBack = state.step.previous() != null && !state.completed
    BackHandler(enabled = canGoBack) { viewModel.goBack() }

    HorizontalPager(state = pagerState) { pageIndex ->
        val pageStep = OnboardingStep.entries[pageIndex]
        when {
            pageStep.isWalkthrough -> WalkthroughSlide(
                step = pageStep,
                onNext = viewModel::advance,
                onSkip = viewModel::skipWalkthroughToCamera
            )
            pageStep.isPermission -> CameraRationaleSlide(
                onAllow = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                onNotNow = viewModel::complete
            )
        }
    }
}
