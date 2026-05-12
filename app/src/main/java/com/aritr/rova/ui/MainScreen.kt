package com.aritr.rova.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aritr.rova.data.RovaSettings
import com.aritr.rova.ui.screens.HistoryScreen
import com.aritr.rova.ui.screens.RecordScreen
import com.aritr.rova.ui.screens.SettingsScreen
import com.aritr.rova.ui.screens.SettingsViewModel
import com.aritr.rova.ui.screens.onboarding.OnboardingScreen
import com.aritr.rova.ui.screens.player.PlayerScreen

@Composable
fun MainScreen() {
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
    val startDestination = if (initialOnboardingCompleted) "record" else "onboarding"
    // R1 redesign — RecordViewModel is no longer hoisted here.
    // The Record screen renders its own bottom nav and owns its VM
    // via its `viewModel: RecordViewModel = viewModel()` default param.
    // SettingsViewModel remains shared between RecordScreen and SettingsScreen.
    val settingsViewModel: SettingsViewModel = viewModel()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            // Phase 2.6 — onboarding gate. Completion (and Skip)
            // navigates to `record` and pops the onboarding entry off
            // the back-stack so a system-back from `record` does not
            // re-enter the flow. UI_NAV_GRAPH §5 back-stack table.
            composable("onboarding") {
                OnboardingScreen(
                    onCompleted = {
                        navController.navigate("record") {
                            popUpTo("onboarding") { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable("record") {
                val toHistory: () -> Unit = {
                    navController.navigate("history") {
                        popUpTo(navController.graph.startDestinationId)
                        launchSingleTop = true
                    }
                }
                RecordScreen(
                    onMergeFinished = toHistory,
                    onNavigateToHistory = toHistory,
                    onNavigateToSettings = {
                        navController.navigate("settings") { launchSingleTop = true }
                    },
                    settingsViewModel = settingsViewModel
                )
            }
            composable("history") {
                HistoryScreen(
                    onNavigateToRecord = {
                        navController.navigate("record") {
                            popUpTo(navController.graph.startDestinationId)
                            launchSingleTop = true
                        }
                    },
                    onOpenPlayer = { sessionId ->
                        // Phase 2.5 — argumented routes do NOT use
                        // launchSingleTop because each (route, args)
                        // pair is its own back-stack entry; reusing
                        // the same NavBackStackEntry across different
                        // sessionIds would re-bind the existing
                        // PlayerViewModel to a fresh manifest, which
                        // would re-create ExoPlayer mid-composition.
                        navController.navigate("player/$sessionId")
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "player/{sessionId}",
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
            ) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
                PlayerScreen(
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("settings") { SettingsScreen(settingsViewModel = settingsViewModel) }
        }
    }
}
