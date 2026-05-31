package com.aritr.rova.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aritr.rova.ui.theme.RovaDarkSurface
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aritr.rova.InitialTab
import com.aritr.rova.data.RovaSettings
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.screens.HistoryScreen
import com.aritr.rova.ui.screens.RecordScreen
import com.aritr.rova.ui.screens.SettingsScreen
import com.aritr.rova.ui.screens.SettingsViewModel
import com.aritr.rova.ui.screens.onboarding.OnboardingScreen
import com.aritr.rova.ui.screens.player.PlayerScreen

@Composable
fun MainScreen(
    initialTab: InitialTab = InitialTab.DEFAULT,
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val navController = rememberNavController()
    // Phase 2.6 — onboarding gate. The first-launch flag drives the
    // NavHost start destination: a fresh install routes to the
    // walkthrough + permission flow; once `onboardingCompleted` flips
    // true (via OnboardingViewModel.advance past the last step or
    // OnboardingViewModel.complete) the gate snaps to `record` for the
    // remainder of the install. The flag is read ONCE per Activity
    // composition via `remember` so a mid-flow flip does not interrupt
    // the active flow's NavHost (the flow itself navigates on
    // completion). UI_NAV_GRAPH §4.1.
    val context = LocalContext.current
    val initialOnboardingCompleted = remember(context) {
        RovaSettings(context).onboardingCompleted
    }
    val startTab = remember(initialTab) {
        when (initialTab) {
            InitialTab.HISTORY -> "history"
            InitialTab.DEFAULT -> "record"
        }
    }
    val startDestination = if (initialOnboardingCompleted) startTab else "onboarding"
    // R1 redesign — RecordViewModel is no longer hoisted here.
    // The Record screen renders its own bottom nav and owns its VM
    // via its `viewModel: RecordViewModel = viewModel()` default param.
    // SettingsViewModel remains shared between RecordScreen and SettingsScreen.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
        ) {
            // Phase 2.6 / M4 (2026-05-27) — onboarding gate. Completion
            // (advance past PERM_CAMERA or "Not now" on Camera rationale)
            // navigates to `record` and pops the onboarding entry off the
            // back-stack so a system-back from `record` does not re-enter
            // the flow. M4 NOTE: Walkthrough Skip now jumps to PERM_CAMERA
            // (NOT past it via the old `complete()` path) — Camera is a
            // hard-block, must be prompted. UI_NAV_GRAPH §5 back-stack table.
            composable("onboarding") {
                RovaDarkSurface {
                    OnboardingScreen(
                        onCompleted = {
                            navController.navigate("record") {
                                popUpTo("onboarding") { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    )
                }
            }
            composable("record") {
                val toHistory: () -> Unit = {
                    navController.navigate("history") {
                        popUpTo(navController.graph.startDestinationId)
                        launchSingleTop = true
                    }
                }
                RovaDarkSurface {
                    RecordScreen(
                        onMergeFinished = toHistory,
                        onNavigateToHistory = toHistory,
                        onNavigateToSettings = {
                            navController.navigate("settings") { launchSingleTop = true }
                        },
                        settingsViewModel = settingsViewModel
                    )
                }
            }
            composable("history") {
                HistoryScreen(
                    onNavigateToRecord = {
                        navController.navigate("record") {
                            popUpTo(navController.graph.startDestinationId)
                            launchSingleTop = true
                        }
                    },
                    onOpenPlayer = { sessionId, side ->
                        // Phase 2.5 — argumented routes do NOT use
                        // launchSingleTop because each (route, args)
                        // pair is its own back-stack entry; reusing
                        // the same NavBackStackEntry across different
                        // sessionIds would re-bind the existing
                        // PlayerViewModel to a fresh manifest, which
                        // would re-create ExoPlayer mid-composition.
                        //
                        // Phase 6.1b smoke-fix #3 — P+L sessions thread
                        // a `side` query arg so the route disambiguates
                        // the two cards per sessionId. Single-mode omits
                        // the query arg so the route stays byte-identical
                        // to the pre-smoke-fix-#3 shape.
                        val route = if (side != null) {
                            "player/$sessionId?side=${side.name}"
                        } else {
                            "player/$sessionId"
                        }
                        navController.navigate(route)
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "player/{sessionId}?side={side}",
                arguments = listOf(
                    navArgument("sessionId") { type = NavType.StringType },
                    navArgument("side") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
                // Phase 6.1b smoke-fix #3 — `side` is an optional query
                // arg; null for single-mode rows, "PORTRAIT" / "LANDSCAPE"
                // for P+L rows. runCatching guards against an unknown
                // enum value (manual deep-link probe etc.) — falls back
                // to null, which the resolver surfaces as Unavailable
                // on P+L manifests.
                val sideStr = backStackEntry.arguments?.getString("side")
                val side = sideStr?.let { runCatching { VideoSide.valueOf(it) }.getOrNull() }
                RovaDarkSurface {
                    PlayerScreen(
                        sessionId = sessionId,
                        side = side,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable("settings") { SettingsScreen(settingsViewModel = settingsViewModel, onBack = { navController.popBackStack() }) }
        }
    }
}
