package com.aritr.rova.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aritr.rova.ui.screens.HistoryScreen
import com.aritr.rova.ui.screens.RecordScreen
import com.aritr.rova.ui.screens.RecordViewModel
import com.aritr.rova.ui.screens.SettingsScreen
import com.aritr.rova.ui.screens.SettingsViewModel
import com.aritr.rova.ui.screens.player.PlayerScreen

private data class MainDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
)

/**
 * Phase 2.5 — top-level routes that own the floating bottom-nav pill.
 * Drill-down / fullscreen routes (currently only `player/{sessionId}`,
 * later `onboarding`) are intentionally absent so the Option-A guard
 * in the [Scaffold]'s `bottomBar` slot collapses the nav surface to
 * empty when the user is on those routes.
 *
 * UI_NAV_GRAPH §5.1 designates this slice (the first new route to
 * need a hidden nav) as the owner of the shell decision; Phase 2.6
 * (onboarding) MUST reuse this same set rather than introducing a
 * second pattern.
 */
private val TOP_LEVEL_ROUTES = setOf("record", "history", "settings")

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    // Slice 3 — RecordViewModel hoisted to MainScreen scope so the
    // bottom NavigationBar can read `serviceState.isPeriodicActive`
    // and gate tab switches without coupling MainScreen directly to
    // the recording service. Activity-scoped (no
    // `viewModelStoreOwner` override): one instance survives every
    // tab switch and binds to `RovaRecordingService` once per
    // activity lifecycle, instead of being re-created on every
    // navigation back to the Record destination.
    val recordViewModel: RecordViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()
    val recordServiceState by recordViewModel.serviceState.collectAsStateWithLifecycle()
    // Phase 2.4 — lock the bottom nav for both the active periodic
    // session AND the post-stop merge. `RecordScreen` already uses
    // the same predicate via its `isUiLocked` field; without the
    // `isMerging` term here, History / Settings / Record tabs stay
    // tappable during the merge HUD because the service flips
    // `isPeriodicActive` off before `isMerging` falls.
    val sessionLocked = recordServiceState.isPeriodicActive ||
        recordServiceState.isMerging
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val destinations = listOf(
        MainDestination("record", "Record", Icons.Default.Videocam),
        MainDestination("history", "History", Icons.Default.History),
        MainDestination("settings", "Settings", Icons.Default.Settings)
    )

    val showBottomNav = currentRoute in TOP_LEVEL_ROUTES
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // Phase 2.5 — Option-A guard. Drill-down routes
            // (`player/{sessionId}` today, `onboarding` in Phase 2.6)
            // suppress the nav by short-circuiting here. The Scaffold
            // slot still runs and consumes 0 dp of inset on this
            // branch, so top-level tabs render with their existing
            // padding contract. UI_NAV_GRAPH §5.1.
            if (!showBottomNav) return@Scaffold
            Column(modifier = Modifier.fillMaxWidth()) {
                // Slice 3 / Phase 2.4 — "Locked during recording"
                // hint pill above the nav. Visible while a periodic
                // session is active OR a post-stop merge is in
                // flight. Carried in the bottomBar slot so it never
                // overlaps the nav itself at any font scale.
                if (sessionLocked) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            tonalElevation = 4.dp,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.semantics {
                                contentDescription =
                                    "Navigation locked during recording"
                            }
                        ) {
                            Text(
                                text = "Locked during recording",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    horizontal = 12.dp,
                                    vertical = 6.dp
                                )
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(32.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        tonalElevation = 8.dp,
                        shadowElevation = 10.dp,
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                        )
                    ) {
                        NavigationBar(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            tonalElevation = 0.dp,
                            // Slice 3 — disabled-state semantics surface
                            // through to assistive tech. Visual dim is
                            // a side effect of `enabled = false` on
                            // each NavigationBarItem; the parent
                            // `disabled` semantics is a redundant but
                            // explicit signal to TalkBack.
                            modifier = if (sessionLocked) {
                                Modifier.semantics { disabled() }
                            } else {
                                Modifier
                            }
                        ) {
                            destinations.forEach { destination ->
                                val selected = currentRoute == destination.route
                                NavigationBarItem(
                                    icon = {
                                        Icon(
                                            destination.icon,
                                            contentDescription = destination.label
                                        )
                                    },
                                    label = { Text(destination.label) },
                                    selected = selected,
                                    enabled = !sessionLocked,
                                    onClick = {
                                        if (sessionLocked) return@NavigationBarItem
                                        if (!selected) {
                                            navController.navigate(destination.route) {
                                                popUpTo(navController.graph.startDestinationId)
                                                launchSingleTop = true
                                            }
                                        }
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        disabledIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                                        disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "record",
            modifier = Modifier.padding(innerPadding)
        ) {
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
                    viewModel = recordViewModel,
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
                    }
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
