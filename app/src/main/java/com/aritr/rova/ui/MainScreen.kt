package com.aritr.rova.ui

import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aritr.rova.RovaApp
import com.aritr.rova.ui.theme.RovaDarkSurface
import androidx.navigation.NavHostController
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
import com.aritr.rova.ui.vault.VaultAuthGate
import com.aritr.rova.ui.vault.VaultScreen

/**
 * B2 review fix — routes that paint full-bleed dark regardless of the app
 * theme (camera viewfinder "record", video "player/…", "onboarding"; each
 * wrapped in RovaDarkSurface). They need light/white system-bar icons even in
 * Light theme — otherwise dark icons render invisibly over the black surface.
 * The argumented player route ("player/{sessionId}?side={side}") is matched by
 * its base segment. Consumed by the single bar writer (RovaTheme) in MainActivity.
 */
fun isPinnedDarkRoute(route: String?): Boolean {
    val base = route?.substringBefore('?')?.substringBefore('/') ?: return false
    return base == "record" || base == "onboarding" || base == "player"
}

@Composable
fun MainScreen(
    initialTab: InitialTab = InitialTab.DEFAULT,
    settingsViewModel: SettingsViewModel = viewModel(),
    navController: NavHostController = rememberNavController(),
) {
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
                // B5 / ADR-0025 — vault entry. Auth-gates on tap, then sets the
                // in-memory unlock and navigates. With no enrolled lock we still
                // navigate (contents remain hidden from the gallery) so the vault
                // screen can show the honest no-lock banner.
                val activity = context as FragmentActivity
                val app = context.applicationContext as RovaApp
                val onOpenVault: () -> Unit = {
                    VaultAuthGate.authenticate(
                        activity = activity,
                        onSucceeded = {
                            app.onVaultAuthSucceeded()
                            navController.navigate("vault") { launchSingleTop = true }
                        },
                        onCancelled = {},
                        onUnavailable = {
                            app.onVaultAuthSucceeded()
                            navController.navigate("vault") { launchSingleTop = true }
                        },
                        // TODO(B5): wire ActivityResult to flip lock on keyguard return
                        launchKeyguard = { intent -> activity.startActivity(intent) },
                    )
                }
                HistoryScreen(
                    onNavigateToRecord = {
                        navController.navigate("record") {
                            popUpTo(navController.graph.startDestinationId)
                            launchSingleTop = true
                        }
                    },
                    onOpenVault = onOpenVault,
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
                route = "player/{sessionId}?side={side}&secure={secure}",
                arguments = listOf(
                    navArgument("sessionId") { type = NavType.StringType },
                    navArgument("side") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    // B5 / ADR-0025 — optional; the vault list passes secure=true.
                    // Default false keeps every existing player route (Library,
                    // recovery) byte-compatible and unsecured.
                    navArgument("secure") {
                        type = NavType.BoolType
                        defaultValue = false
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
                // B5 / ADR-0025 — deterministic FLAG_SECURE from frame 1. The
                // vault list passes secure=true so the window is secured the
                // instant the player composes, instead of waiting for the async
                // isVaulted manifest read. That race was the on-device hole: the
                // vault destination's FLAG_SECURE ref is released as the player
                // covers it (MainScreen disposes the vault composition), so if
                // the player hadn't yet acquired via isVaulted the flag dropped
                // to zero and steady-state playback was screenshottable.
                val secure = backStackEntry.arguments?.getBoolean("secure") ?: false
                RovaDarkSurface {
                    PlayerScreen(
                        sessionId = sessionId,
                        side = side,
                        secure = secure,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable("vault") {
                // B5 / ADR-0025 — `onLeaveVault()` must fire on BOTH the back
                // chevron and a system-back, but NOT when the vault is merely
                // covered by the player (vault -> player). Nav Compose disposes
                // the vault destination's composition while the player is on top,
                // so a dispose-based relock would fire on the way IN to the
                // player and then bounce the user out on the way back (the
                // re-entering vault's auto-relock LaunchedEffect would see the
                // stale locked state and pop again). Instead, relock only on an
                // actual pop: the back chevron callback and a BackHandler for
                // system back. Navigating to the player leaves the vault
                // unlocked, which is correct — the user is still viewing vault
                // content, and the player applies its own FLAG_SECURE.
                val app = context.applicationContext as RovaApp
                val noLock = remember(context) {
                    BiometricManager.from(context).canAuthenticate(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    ) != BiometricManager.BIOMETRIC_SUCCESS
                }
                val leaveVault: () -> Unit = {
                    app.onLeaveVault()
                    navController.popBackStack()
                }
                androidx.activity.compose.BackHandler { leaveVault() }
                VaultScreen(
                    onOpenPlayer = { sessionId, side ->
                        // B5 / ADR-0025 — secure=true: every vault playback
                        // secures the window from the first frame (deterministic;
                        // not dependent on the async isVaulted manifest read).
                        val route = if (side != null) {
                            "player/$sessionId?side=${side.name}&secure=true"
                        } else {
                            "player/$sessionId?secure=true"
                        }
                        navController.navigate(route)
                    },
                    onBack = leaveVault,
                    showNoLockWarning = noLock,
                )
            }
            composable("settings") { SettingsScreen(settingsViewModel = settingsViewModel, onBack = { navController.popBackStack() }) }
        }
    }
}
